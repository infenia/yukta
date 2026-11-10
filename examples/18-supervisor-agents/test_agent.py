# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Checks what agent.py decides, without a server: python3 test_agent.py"""
import os
import unittest

os.environ.setdefault("YUKTA_AGENT_TOKEN", "yag_test")
import agent  # noqa: E402


class DecideTest(unittest.TestCase):
    def test_a_failed_node_pauses_its_run(self):
        path, body = agent.decide(
            {
                "cursor": 7,
                "kind": "EVENT",
                "signal": {
                    "id": "e7",
                    "event": {"executionId": "run1", "nodeId": "build", "status": "FAILURE"},
                },
            }
        )
        self.assertEqual(path, "/directives")
        self.assertEqual(body["command"], {"type": "PauseWorkflow", "executionId": "run1"})
        self.assertEqual(body["cause"], "event:e7")
        self.assertTrue(body["reason"])

    def test_a_review_gets_no_opinion(self):
        self.assertEqual(
            agent.decide({"cursor": 1, "kind": "REVIEW", "signal": {"id": "r1"}}),
            ("/reviews/r1", {"verdict": "NO_OPINION"}),
        )

    def test_other_signals_need_nothing(self):
        self.assertIsNone(
            agent.decide({"cursor": 2, "kind": "EVENT", "signal": {"event": {"status": "SUCCESS"}}})
        )
        self.assertIsNone(agent.decide({"cursor": 3, "kind": "TICK", "signal": {}}))


if __name__ == "__main__":
    unittest.main()
