"""search_flights against flight-search's contract (api-specs/flight-search-api.yaml), the API-first source of truth.

The tool reads some fields of an itinerary; each must be in the contract, and required unless the tool copes with it
being absent. The fake flight-search the tests use must answer only with what the contract allows, so the tests cannot
pass against a shape the real service never sends.
"""

from pathlib import Path

import pytest
import yaml

from tests.flight_world import BOM_JFK, DXB_LHR

ROOT = Path(__file__).resolve().parents[3]
SPEC = yaml.safe_load((ROOT / "api-specs" / "flight-search-api.yaml").read_text(encoding="utf-8"))
SCHEMAS = SPEC["components"]["schemas"]

# What traveller_assistant.tools reads, and whether it copes with the field being absent.
READ = {
    "Itinerary": {"legs": True, "connections": True, "stops": True, "totalDurationMinutes": True, "fareFrom": True,
                  "currency": True},
    "FlightLeg": {"flightNumber": True, "origin": True, "destination": True, "departureTime": True,
                  "arrivalTime": True, "status": False, "revisedDeparture": False},
    "Connection": {"arrivalAirport": True, "departureAirport": True, "layoverMinutes": True,
                   "requiresAirportTransfer": True, "transferMinutes": True},
}


@pytest.mark.parametrize("schema", READ)
def test_every_field_the_tool_relies_on_is_in_the_contract_and_required_where_the_tool_needs_it(schema):
    properties, required = SCHEMAS[schema]["properties"], set(SCHEMAS[schema].get("required", []))

    assert [f for f in READ[schema] if f not in properties] == []
    assert [f for f, needed in READ[schema].items() if needed and f not in required] == []


def test_the_search_is_the_one_in_the_contract_with_the_parameters_the_client_sends():
    search = SPEC["paths"]["/api/v1/itineraries"]["get"]

    assert {p["name"] for p in search["parameters"]} == {"origin", "destination", "date"}
    assert "400" in search["responses"]


def test_flight_search_lets_anyone_search_so_the_server_needs_no_credentials():
    security = ROOT / "services/flight-search/src/main/java/com/skyhub/flightsearch/config/SecurityConfig.java"

    assert '.requestMatchers(GET, "/api/v1/hubs", "/api/v1/itineraries"' in security.read_text(encoding="utf-8")
    assert ".permitAll()" in security.read_text(encoding="utf-8")


@pytest.mark.parametrize("itinerary", BOM_JFK + DXB_LHR, ids=lambda i: "+".join(l["flightNumber"] for l in i["legs"]))
def test_the_fake_flight_search_answers_only_with_what_the_contract_allows(itinerary):
    def check(value, schema):
        allowed = set(SCHEMAS[schema]["properties"])
        assert set(value) <= allowed, f"{schema} has fields the contract does not: {set(value) - allowed}"
        assert set(SCHEMAS[schema].get("required", [])) <= set(value), f"{schema} misses required fields"

    check(itinerary, "Itinerary")
    for leg in itinerary["legs"]:
        check(leg, "FlightLeg")
        assert leg.get("status", "SCHEDULED") in SCHEMAS["FlightStatus"]["enum"]
    for connection in itinerary["connections"]:
        check(connection, "Connection")
