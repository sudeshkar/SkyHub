"""Reading the traveller's own booking with their manage token, and giving the model only what it needs (ADR-0094)."""

import json

import httpx
import pytest

from traveller_assistant.booking import BOOKING_ENDED, CANNOT_OPEN, BookingClient, ServiceError
from traveller_assistant.tools import ABOUT_BOOKING, GetMyBooking, ToolInputError
from tests.booking_world import GOOD_TOKEN, PNR, REFERENCE, SECRETS, BookingWorld


def read(world=None, reference=REFERENCE, token=GOOD_TOKEN):
    world = world or BookingWorld()
    return GetMyBooking(BookingClient(world.booking(), world.search())).run(reference=reference, booking_token=token)


def test_shows_the_booking_flight_by_flight_with_times_seats_and_who_is_checked_in():
    booking = read()

    assert booking["status"] == "CONFIRMED" and booking["passengers"] == 2
    assert booking["flights"][0] == {
        "flight": "SK201", "date": "2026-12-01", "from": "BOM", "to": "DXB", "departs": "2026-12-01T02:00:00Z",
        "arrives": "2026-12-01T05:10:00Z", "status": "SCHEDULED",
        "seats": {"passenger 1": "12A", "passenger 2": "12B"}, "checked_in": ["passenger 1"]}
    assert booking["flights"][1]["checked_in"] == []
    assert "refund" not in booking
    assert booking["note"] == ABOUT_BOOKING


def test_a_delay_on_the_booking_shows_the_new_time_and_the_reason():
    delayed = read()["flights"][1]

    assert (delayed["status"], delayed["now_departs"], delayed["reason"]) == (
        "DELAYED", "2026-12-01T08:15:00Z", "Late inbound aircraft")


def test_never_gives_the_model_a_name_the_contact_email_the_reference_a_barcode_or_the_token():
    shown = json.dumps(read())

    assert [secret for secret in SECRETS if secret in shown] == []


def test_the_note_says_why_there_are_no_names_and_where_changes_are_made():
    assert "not named" in ABOUT_BOOKING and "UTC" in ABOUT_BOOKING and "Manage booking page" in ABOUT_BOOKING


def test_sends_the_token_to_the_booking_service_only_and_nothing_to_flight_search():
    world = BookingWorld()

    read(world)

    to_booking = [r for r in world.requests if r.url.host == "booking.test"]
    to_search = [r for r in world.requests if r.url.host == "flight-search.test"]
    assert all(r.headers["authorization"] == f"Bearer {GOOD_TOKEN}" for r in to_booking)
    assert all("authorization" not in r.headers for r in to_search)
    assert all(GOOD_TOKEN not in str(r.url) for r in world.requests)


def test_asks_a_bounded_number_of_questions_one_per_flight_for_check_in_and_times():
    world = BookingWorld()

    read(world)

    assert len(world.requests) == 1 + 1 + 2 + 2


def test_a_cancelled_booking_shows_its_refund_and_nobody_checked_in():
    cancelled = dict(PNR, status="CANCELLED", refund={"status": "PENDING", "amount": 1240.0, "currency": "USD"})
    world = BookingWorld(pnr=cancelled, check_ins={0: (409, {"detail": "The booking is cancelled"}),
                                                   1: (409, {"detail": "The booking is cancelled"})})

    booking = read(world)

    assert booking["status"] == "CANCELLED"
    assert booking["refund"] == {"status": "PENDING", "amount": 1240.0, "currency": "USD"}
    assert all(f["checked_in"] == [] for f in booking["flights"])


def test_a_flight_flight_search_does_not_know_is_shown_without_times():
    booking = read(BookingWorld(departures={}))

    first = booking["flights"][0]
    assert first["flight"] == "SK201" and "departs" not in first and first["seats"]["passenger 1"] == "12A"


def test_an_ended_session_asks_the_traveller_to_open_the_booking_again():
    with pytest.raises(ServiceError, match="session has ended") as raised:
        read(token="an-expired-token")

    assert str(raised.value) == BOOKING_ENDED


@pytest.mark.parametrize("status", [403, 404])
def test_another_booking_or_no_booking_is_the_same_answer_so_nothing_is_learnt(status):
    with pytest.raises(ServiceError) as raised:
        read(BookingWorld(booking_status=status))

    assert str(raised.value) == CANNOT_OPEN


def test_a_booking_service_that_is_down_says_to_try_again_and_not_where_it_is():
    with pytest.raises(ServiceError, match="not answering") as raised:
        read(BookingWorld(fail=httpx.ConnectError("refused")))

    assert "booking.test" not in str(raised.value)


@pytest.mark.parametrize("reference, token", [("abc234", GOOD_TOKEN), ("ABC23", GOOD_TOKEN), ("ABC234/../x", GOOD_TOKEN),
                                              (REFERENCE, ""), (REFERENCE, "x" * 5000)])
def test_refuses_a_reference_or_token_of_the_wrong_shape_before_asking_anyone(reference, token):
    world = BookingWorld()

    with pytest.raises(ToolInputError, match="no booking open"):
        read(world, reference=reference, token=token)
    assert world.requests == []
