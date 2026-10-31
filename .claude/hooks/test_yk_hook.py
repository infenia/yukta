#!/usr/bin/env python3
"""Offline test of yk-hook.py: the command rewrite (what is denied, what passes). Run: python3 .claude/hooks/test_yk_hook.py"""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("yk_hook", Path(__file__).with_name("yk-hook.py"))
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)

CASES = [
    ("./gradlew :core:test --tests X", "yk gradle :core:test --tests X"),
    ("FOO=1 timeout 60 ./gradlew -q --offline :core:compileJava", "yk gradle :core:compileJava"),
    ("cd ui && pnpm lint", "yk tool -C ui pnpm lint"),
    ("go test ./...", "yk tool -C cli go test ./..."),
    ("go version", None),
    ("./gradlew bootRun", None),
    ("./gradlew :boot:bootRun --args=x", None),
    ("echo bootRun; ./gradlew test", "yk gradle test"),
    ("git commit -m 'fix bootRun' && npx prettier .", "yk tool -C ui npx prettier ."),
    ("git commit -m 'run gradle test && pnpm lint'", None),
    ("ls | grep gradle", None),
    ("git status", None),
    ("bash -c 'cd ui && pnpm test'", "yk tool -C ui pnpm test"),
    ('sh -c "./gradlew :core:test"', "yk gradle :core:test"),
    ("ui/node_modules/.bin/vitest run", "yk tool -C ui ui/node_modules/.bin/vitest run"),
    ("yarn test", "yk tool -C ui yarn test"),
    ("./gradlew :core:test --tests 'a.B.c d'", "yk gradle :core:test --tests 'a.B.c d'"),
    ("yk gradle :core:test", None),
    ("./gradlew test 2>&1 | tail -5", "yk gradle test"),
]


class Rewrite(unittest.TestCase):
    def test_cases(self):
        for cmd, use in CASES:
            with self.subTest(cmd=cmd):
                self.assertEqual(hook.rewrite(cmd), use)


if __name__ == "__main__":
    unittest.main()
