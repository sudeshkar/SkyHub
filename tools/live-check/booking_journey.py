"""Drives a booking through the REAL chain: flight search -> seats -> hold -> payment -> booking -> email, then the account that sees it,
then the operations side (a cancelled flight, search following it, a rebooking that keeps the connection, a cancellation and refund).

Needs the flight search, booking and notification services, Kafka and Mailpit running, and a staff account:
set SKYHUB_BOOTSTRAP_ADMIN_USERNAME / SKYHUB_BOOTSTRAP_ADMIN_PASSWORD on the booking service and give the same here as
LIVE_STAFF_USERNAME / LIVE_STAFF_PASSWORD. Prints PASS/FAIL per check and exits non-zero if any fails. See README.md in this folder."""
import datetime
import time
import os
import random
import uuid

from _live import BOOKING, SEARCH, call, check, count_mails, finish, info, link_token, wait_mail, wait_until

STAFF = (os.environ.get("LIVE_STAFF_USERNAME", "live.admin"), os.environ.get("LIVE_STAFF_PASSWORD", "live-admin-passphrase"))
PASSWORD = "a long enough passphrase"
run = uuid.uuid4().hex[:8]
TRAVELLER = f"live.trip.{run}@example.com"
FIRST_NAMES = ["Priya", "Anil"]


def search(origin, destination, date):
    status, found = call("GET", f"{SEARCH}/api/v1/itineraries?origin={origin}&destination={destination}&date={date}")
    return found if status == 200 else []


def seat_map(flight, date):
    status, seats = call("GET", f"{BOOKING}/api/v1/flights/{flight}/departures/{date}/seats")
    return {s["seatNumber"]: s for s in seats["seats"]} if status == 200 else {}


def free_economy(flight, date):
    return [n for n, s in seat_map(flight, date).items() if s["status"] == "AVAILABLE" and s["cabin"] == "ECONOMY"]


def day(leg):
    return leg["departureTime"][:10]


# ---- 0. find a trip that exists: a one-stop itinerary with seats on both flights ---------------------------------------------------------
trip = None
for ahead in range(3, 10):
    date = (datetime.date.today() + datetime.timedelta(days=ahead)).isoformat()
    for itinerary in search("CMB", "LHR", date):
        legs = itinerary["legs"]
        if len(legs) == 2 and all(len(free_economy(l["flightNumber"], day(l))) >= 6 for l in legs):
            trip = itinerary
            break
    if trip:
        break
check("the real flight search offers a one-stop trip CMB to LHR with free economy seats on both flights", trip is not None)
if not trip:
    finish()
LEGS = trip["legs"]
FIRST, SECOND = LEGS[0], LEGS[1]
SEGMENTS = [{"flightNumber": l["flightNumber"], "departureDate": day(l)} for l in LEGS]
info(f"trip: {FIRST['flightNumber']} {day(FIRST)} then {SECOND['flightNumber']} {day(SECOND)}")

# ---- 1. quote, hold, and a payment that is declined ---------------------------------------------------------------------------------
expected = round(trip["fareFrom"] * 2, 2)
status, quote = call("POST", f"{BOOKING}/api/v1/quotes", {"segments": SEGMENTS, "passengerCount": 2})
check("the quote for two passengers is twice the itinerary's fare", status == 200 and float(quote["amount"]) == expected, (status, quote, expected))

chosen = {l["flightNumber"]: random.sample(free_economy(l["flightNumber"], day(l)), 3) for l in LEGS}   # two for the booking, one spare for later
seats = [[chosen[FIRST["flightNumber"]][p], chosen[SECOND["flightNumber"]][p]] for p in range(2)]   # per passenger, one seat per flight
items = [{"flightNumber": l["flightNumber"], "departureDate": day(l), "seatNumber": chosen[l["flightNumber"]][p]} for l in LEGS for p in range(2)]
status, hold = call("POST", f"{BOOKING}/api/v1/holds", {"items": items})
check("the four seats are held (201)", status == 201 and "holdId" in hold, (status, hold))
held_map = seat_map(FIRST["flightNumber"], day(FIRST))
check("the seat map shows them HELD", all(held_map[chosen[FIRST["flightNumber"]][p]]["status"] == "HELD" for p in range(2)))

booking_body = {
    "paymentToken": "tok_visa", "expectedAmount": expected, "holdId": hold.get("holdId"), "contactEmail": TRAVELLER, "segments": SEGMENTS,
    "passengers": [{"firstName": FIRST_NAMES[p], "lastName": "Perera", "seats": seats[p]} for p in range(2)],
}
status, declined = call("POST", f"{BOOKING}/api/v1/pnrs", {**booking_body, "paymentToken": "tok_declined"}, headers={"Idempotency-Key": str(uuid.uuid4())})
check("a declined card is a 402 and books nothing", status == 402, (status, declined))
check("the held seats are still held, not booked, after the decline",
      seat_map(FIRST["flightNumber"], day(FIRST))[chosen[FIRST["flightNumber"]][0]]["status"] == "HELD")
check("no confirmation was sent for the declined attempt", count_mails(TRAVELLER, "is confirmed") == 0)
status, wrong_price = call("POST", f"{BOOKING}/api/v1/pnrs", {**booking_body, "expectedAmount": 1.0}, headers={"Idempotency-Key": str(uuid.uuid4())})
check("a price that has changed is refused (409) and nothing is charged", status == 409, (status, wrong_price))

# ---- 2. the booking, and asking again ------------------------------------------------------------------------------------------------
KEY = str(uuid.uuid4())
status, booking = call("POST", f"{BOOKING}/api/v1/pnrs", booking_body, headers={"Idempotency-Key": KEY})
check("the booking is made (201) with a reference", status == 201 and len(booking.get("locator", "")) == 6, (status, booking))
if status != 201:
    finish()
LOCATOR = booking["locator"]
info(f"booking {LOCATOR}")
status, replay = call("POST", f"{BOOKING}/api/v1/pnrs", booking_body, headers={"Idempotency-Key": KEY})
check("asking again with the same key answers 200 with the same booking", status == 200 and replay["locator"] == LOCATOR, (status, replay))
status, _ = call("POST", f"{BOOKING}/api/v1/pnrs", {**booking_body, "contactEmail": "someone.else@example.com"}, headers={"Idempotency-Key": KEY})
check("the same key for a different booking is refused (422)", status == 422, status)

confirmation = wait_mail(TRAVELLER, f"{LOCATOR} is confirmed")
check("the confirmation email arrived in Mailpit with the reference", confirmation is not None)
check("it names both flights", bool(confirmation) and FIRST["flightNumber"] in confirmation["Text"] and SECOND["flightNumber"] in confirmation["Text"])
time.sleep(4)   # long enough for a duplicate to arrive if the replay had sent one
check("asking again did not send a second confirmation", count_mails(TRAVELLER, f"{LOCATOR} is confirmed") == 1)
after = seat_map(FIRST["flightNumber"], day(FIRST))
check("the seats are BOOKED on the seat map", all(after[chosen[FIRST["flightNumber"]][p]]["status"] == "BOOKED" for p in range(2)))

# ---- 3. looking after the booking as a guest --------------------------------------------------------------------------------------------
status, _ = call("POST", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/sessions", {"lastName": "Nobody"})
check("a wrong last name gets 404, the same as an unknown reference", status == 404, status)
status, session = call("POST", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/sessions", {"lastName": "perera"})
check("the right last name (any case) opens a session (201)", status == 201, status)
MANAGE = session["accessToken"]
status, pnr = call("GET", f"{BOOKING}/api/v1/pnrs/{LOCATOR}", token=MANAGE)
check("the booking is shown with its two passengers", status == 200 and len(pnr["passengers"]) == 2, status)
spare = chosen[FIRST["flightNumber"]][2]
old_seat = chosen[FIRST["flightNumber"]][0]
status, changed = call("PUT", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/seat-assignments", {"passengerIndex": 0, "segmentIndex": 0, "seatNumber": spare}, token=MANAGE)
check("a seat can be changed (200)", status == 200 and changed["passengers"][0]["seats"][0] == spare, (status, changed))
now = seat_map(FIRST["flightNumber"], day(FIRST))
check("the old seat is free again and the new one is booked", now[old_seat]["status"] == "AVAILABLE" and now[spare]["status"] == "BOOKED")

# ---- 3b. signing out of the booking ends every session of it, a copied token included (ADR-0057) --------------------------------------------
_, one = call("POST", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/sessions", {"lastName": "perera"})
_, copy = call("POST", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/sessions", {"lastName": "perera"})   # what somebody who saw the cookie would hold
status, _ = call("GET", f"{BOOKING}/api/v1/pnrs/{LOCATOR}", token=copy["accessToken"])
check("a second session of the booking works before anyone signs out (200)", status == 200, status)
status, _ = call("DELETE", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/sessions", token=one["accessToken"])
check("signing out of the booking is accepted (204)", status == 204, status)
for label, t in (("the session that signed out", one["accessToken"]), ("a copy of another token for the same booking", copy["accessToken"]), ("the earlier session", MANAGE)):
    status, _ = call("GET", f"{BOOKING}/api/v1/pnrs/{LOCATOR}", token=t)
    check(f"{label} is refused at once (401)", status == 401, status)
status, session = call("POST", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/sessions", {"lastName": "perera"})
check("proving the last name again opens a new session (201)", status == 201, status)
MANAGE = session["accessToken"]
status, _ = call("GET", f"{BOOKING}/api/v1/pnrs/{LOCATOR}", token=MANAGE)
check("and it works (200)", status == 200, status)

# ---- 4. an account sees the booking that was made before the account existed ----------------------------------------------------------
call("POST", f"{BOOKING}/api/v1/accounts/password-links", {"email": TRAVELLER})
link = wait_mail(TRAVELLER, "Finish creating")
call("POST", f"{BOOKING}/api/v1/accounts/passwords", {"token": link_token(link, "/account/set-password") if link else "", "password": PASSWORD})
status, login = call("POST", f"{BOOKING}/api/v1/accounts/sessions", {"email": TRAVELLER, "password": PASSWORD})
check("the traveller signs up and signs in with the address the booking was made with", status == 201, status)
ACCOUNT = login["accessToken"] if status == 201 else None
status, mine = call("GET", f"{BOOKING}/api/v1/accounts/me/bookings", token=ACCOUNT)
check("Your bookings lists the booking made before the account existed", status == 200 and [b["locator"] for b in mine["bookings"]] == [LOCATOR], mine)
status, upcoming = call("GET", f"{BOOKING}/api/v1/accounts/me/bookings?when=upcoming", token=ACCOUNT)
status2, past = call("GET", f"{BOOKING}/api/v1/accounts/me/bookings?when=past", token=ACCOUNT)
check("it is upcoming, not past", [b["locator"] for b in upcoming["bookings"]] == [LOCATOR] and past["bookings"] == [])
status, opened = call("POST", f"{BOOKING}/api/v1/accounts/me/bookings/{LOCATOR}/sessions", token=ACCOUNT)
check("it can be opened from the account without its last name (201)", status == 201, status)
status, _ = call("GET", f"{BOOKING}/api/v1/pnrs/{LOCATOR}", token=opened["accessToken"])
check("and the token that gives is an ordinary booking token (200)", status == 200, status)

# ---- 5. operations: a cancelled flight ---------------------------------------------------------------------------------------------------
status, staff = call("POST", f"{BOOKING}/api/v1/ops/sessions", {"username": STAFF[0], "password": STAFF[1]})
check("staff sign in (201)", status == 201, status)
OPS = staff["accessToken"] if status == 201 else None
status, affected = call("GET", f"{BOOKING}/api/v1/ops/flights/{SECOND['flightNumber']}/departures/{day(SECOND)}/bookings", token=OPS)
check("the booking is among those on the second flight", status == 200 and LOCATOR in [i["locator"] for i in affected["items"]], status)
status, _ = call("POST", f"{BOOKING}/api/v1/ops/disruptions",
                 {"flightNumber": SECOND["flightNumber"], "departureDate": day(SECOND), "type": "CANCELLED", "reason": "Live check"}, token=OPS)
check("staff record the second flight as cancelled (200)", status == 200, status)
notice = wait_mail(TRAVELLER, f"flight {SECOND['flightNumber']}")
check("the traveller is told by email, naming the flight", notice is not None and "cancel" in (notice["Subject"] + notice["Text"]).lower())
status, refused = call("POST", f"{BOOKING}/api/v1/quotes", {"segments": [SEGMENTS[1]], "passengerCount": 1})
check("a cancelled flight can no longer be sold (409)", status == 409, (status, refused))
gone = wait_until(lambda: all(SECOND["flightNumber"] not in [l["flightNumber"] for l in i["legs"]]
                              for i in search("CMB", "LHR", day(FIRST))) or None, timeout=45)
check("flight search, following the disruption topic, stops offering the cancelled flight", bool(gone))

# ---- 6. a rebooking that keeps the connection ----------------------------------------------------------------------------------------------
arrival = FIRST["arrivalTime"]
candidates = []
for ahead in (0, 1):
    on = (datetime.date.fromisoformat(day(SECOND)) + datetime.timedelta(days=ahead)).isoformat()
    for itinerary in search("DXB", "LHR", on):
        leg = itinerary["legs"][0]
        # Another departure, not another flight number: the same flight on the next day is a perfectly good replacement.
        if len(itinerary["legs"]) == 1 and (leg["flightNumber"], day(leg)) != (SECOND["flightNumber"], day(SECOND)) and leg["status"] != "CANCELLED":
            candidates.append(leg)
late_enough = [l for l in candidates if l["departureTime"] >= (datetime.datetime.fromisoformat(arrival.replace("Z", "+00:00"))
                                                               + datetime.timedelta(minutes=60)).isoformat().replace("+00:00", "Z")]
check("there is another DXB to LHR departure that leaves at least an hour after the first flight lands", bool(late_enough))
if late_enough:
    target = late_enough[0]
    info(f"moving to {target['flightNumber']} on {day(target)}")
    status, bulk = call("POST", f"{BOOKING}/api/v1/ops/rebookings",
                        {"moves": [{"locator": LOCATOR, "segmentIndex": 1, "flightNumber": target["flightNumber"], "departureDate": day(target)}]}, token=OPS)
    result = bulk["results"][0] if status == 200 else {}
    check("the bulk endpoint moves the booking onto it (MOVED)", result.get("outcome") == "MOVED", (status, bulk))
    check("with a seat for each passenger", len(result.get("seats") or []) == 2, result)
    moved = wait_mail(TRAVELLER, "has moved to")
    check("the traveller is emailed the new flight", moved is not None and target["flightNumber"] in moved["Subject"])
    status, pnr = call("GET", f"{BOOKING}/api/v1/pnrs/{LOCATOR}", token=MANAGE)
    check("the booking now shows the new second flight", status == 200 and pnr["segments"][1]["flightNumber"] == target["flightNumber"], pnr)
    status, again = call("POST", f"{BOOKING}/api/v1/ops/rebookings",
                         {"moves": [{"locator": LOCATOR, "segmentIndex": 1, "flightNumber": target["flightNumber"], "departureDate": day(target)}]}, token=OPS)
    check("asking again changes nothing (UNCHANGED), so a dropped connection is safe to repeat", again["results"][0]["outcome"] == "UNCHANGED", again)

    # the connection rule, live: cancel the FIRST flight too, and try to move it to a flight that lands after the onward one leaves
    call("POST", f"{BOOKING}/api/v1/ops/disruptions",
         {"flightNumber": FIRST["flightNumber"], "departureDate": day(FIRST), "type": "CANCELLED", "reason": "Live check"}, token=OPS)
    # Two days on: it lands after the onward flight (the next day's) has already left, so no connection can be made.
    two_days = (datetime.date.fromisoformat(day(FIRST)) + datetime.timedelta(days=2)).isoformat()
    too_late = [i["legs"][0] for i in search("CMB", "DXB", two_days) if len(i["legs"]) == 1]
    if too_late:
        status, bulk = call("POST", f"{BOOKING}/api/v1/ops/rebookings",
                            {"moves": [{"locator": LOCATOR, "segmentIndex": 0, "flightNumber": too_late[0]["flightNumber"], "departureDate": day(too_late[0])}]}, token=OPS)
        result = bulk["results"][0] if status == 200 else {}
        check("a replacement that lands after the onward flight has left is REFUSED, and says which flight it would miss",
              result.get("outcome") == "REFUSED" and target["flightNumber"] in (result.get("detail") or ""), (status, bulk))
    else:
        info("no CMB to DXB flight two days on to test the connection rule with; skipped")

# ---- 7. cancelling, and the money ----------------------------------------------------------------------------------------------------------
status, cancelled = call("POST", f"{BOOKING}/api/v1/pnrs/{LOCATOR}/cancellation", token=MANAGE)
check("the traveller cancels (200)", status == 200 and cancelled["status"] == "CANCELLED", (status, cancelled))
refund = (cancelled or {}).get("refund") or {}
check("the whole 1700.00 USD is refunded", refund.get("status") == "REFUNDED" and float(refund.get("amount", 0)) == expected, refund)
cancel_mail = wait_mail(TRAVELLER, f"booking {LOCATOR} is cancelled")
check("the cancellation email arrived and mentions the refund", cancel_mail is not None and "refund" in cancel_mail["Text"].lower())
check("the seats were released", seat_map(FIRST["flightNumber"], day(FIRST)).get(spare, {}).get("status") == "AVAILABLE")
status, upcoming = call("GET", f"{BOOKING}/api/v1/accounts/me/bookings?when=upcoming", token=ACCOUNT)
status2, past = call("GET", f"{BOOKING}/api/v1/accounts/me/bookings?when=past", token=ACCOUNT)
check("the cancelled booking moves from upcoming to past", upcoming["bookings"] == [] and [b["locator"] for b in past["bookings"]] == [LOCATOR])

finish()
