"""Tests for the parts of stack.py that decide things (no Docker, no Java): keys, what each service is given, and what may be removed.

Run: python tools/live-check/test_stack.py
"""
import base64
import json
import unittest

import stack


class Keys(unittest.TestCase):
    def test_makes_four_different_keys_of_32_bytes(self):
        keys = stack.new_keys()

        self.assertEqual(set(keys), {"pii", "manage", "boarding", "link"})
        self.assertEqual(len(set(keys.values())), 4)
        for value in keys.values():
            self.assertEqual(len(base64.b64decode(value)), 32)

    def test_makes_new_keys_each_time(self):
        self.assertNotEqual(stack.new_keys(), stack.new_keys())


class WhatEachServiceIsGiven(unittest.TestCase):
    keys = {"pii": "P", "manage": "M", "boarding": "B", "link": "L"}

    def test_booking_gets_every_key_the_throwaway_database_and_the_bootstrap_admin(self):
        env = stack.service_env("booking", self.keys)

        self.assertEqual(env["SKYHUB_PII_KEY"], "P")
        self.assertEqual(env["SKYHUB_MANAGE_TOKEN_KEY"], "M")
        self.assertEqual(env["SKYHUB_BOARDING_PASS_KEY"], "B")
        self.assertEqual(env["SKYHUB_LINK_TOKEN_KEY"], "L")
        self.assertEqual(env["DB_URL"], "jdbc:postgresql://localhost:5432/booking_live")
        self.assertEqual(env["SKYHUB_BOOTSTRAP_ADMIN_USERNAME"], "live.admin")
        self.assertEqual(env["SKYHUB_BOOTSTRAP_ADMIN_PASSWORD"], "live-admin-passphrase")

    def test_notification_gets_the_same_link_key_and_none_of_bookings_other_secrets(self):
        env = stack.service_env("notification", self.keys)

        self.assertEqual(env["SKYHUB_LINK_TOKEN_KEY"], "L")
        for private in ("SKYHUB_PII_KEY", "SKYHUB_MANAGE_TOKEN_KEY", "SKYHUB_BOARDING_PASS_KEY", "DB_URL", "SKYHUB_BOOTSTRAP_ADMIN_PASSWORD"):
            self.assertNotIn(private, env)

    def test_notification_can_be_given_a_link_key_that_does_not_match_for_the_failure_path(self):
        wrong = stack.service_env("notification", self.keys, mismatched_link_key=True)

        self.assertNotEqual(wrong["SKYHUB_LINK_TOKEN_KEY"], "L")
        self.assertEqual(len(base64.b64decode(wrong["SKYHUB_LINK_TOKEN_KEY"])), 32)

    def test_flight_search_needs_no_secrets(self):
        self.assertEqual(stack.service_env("flight-search", self.keys), {})

    def test_an_unknown_service_is_refused(self):
        with self.assertRaises(ValueError):
            stack.service_env("website", self.keys)


class WhatMayBeRemoved(unittest.TestCase):
    def test_only_the_throwaway_database_is_ever_dropped(self):
        self.assertEqual(stack.drop_database_sql(), 'DROP DATABASE IF EXISTS booking_live WITH (FORCE);')
        self.assertNotIn("booking ", stack.drop_database_sql())

    def test_only_containers_this_run_started_are_stopped(self):
        # postgres and mailpit were already running before: they are left alone.
        self.assertEqual(stack.containers_to_stop(started_by_us=["kafka"]), ["kafka"])
        self.assertEqual(stack.containers_to_stop(started_by_us=[]), [])

    def test_containers_already_running_are_not_counted_as_ours(self):
        wanted = ["postgres", "kafka", "mailpit"]
        running = ["postgres", "mailpit"]

        self.assertEqual(stack.containers_we_must_start(wanted, running), ["kafka"])


class State(unittest.TestCase):
    def test_state_round_trips_through_json_and_holds_no_key(self):
        state = {"pids": {"booking": 123}, "started_containers": ["kafka"], "notification_key": "wrong"}

        again = json.loads(stack.dump_state(state))

        self.assertEqual(again, state)
        self.assertNotIn("SKYHUB", stack.dump_state(state))


if __name__ == "__main__":
    unittest.main()
