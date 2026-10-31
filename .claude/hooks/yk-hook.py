#!/usr/bin/env python3
"""Claude Code hooks that route builds, tests and checks of this repo through Yukta (`yk`, the yukta skill).

Registered in .claude/settings.json as  python3 "$(git rev-parse --show-toplevel)"/.claude/hooks/yk-hook.py <event>
so they work whatever folder Claude starts in. They live in settings, not only in the `yukta-dogfood` mod, because a mod
works only after `/plugin install`; these work in every checkout, worktree and subagent of the repo.

  session-start  remember the uncommitted changes the session began with, start the dev server, put yk on PATH, and say how to
                 build (the full yukta skill comes from CLAUDE.md, which imports it, so it is there after /compact and in subagents)
  prompt         every prompt gets one line: builds, tests and checks go through yk
  pre-bash       deny ./gradlew, gradle, go test|vet|build|fmt, pnpm|npm|npx|yarn|bun and node_modules/.bin tools (also inside
                 `bash -c '...'`), naming the yk command to use instead
  stop           when the session changed code and no `yk check` passed on it, ask once to run it

Off: YUKTA_DOGFOOD=off. Headless fixers (YUKTA_HEAL_FIXER) run gradle themselves and skip all of it.
When the `yukta-dogfood` mod is installed it adds the same context; this script then only enforces (pre-bash, stop), which are idempotent.
"""
import json
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

CODE = ["*.java", "*.kts", "*.gradle", "*.go", "*.ts", "*.tsx", "*.json"]
HINT = (
    "Build, test and check this repo only through Yukta (yukta skill, imported by CLAUDE.md): `yk gradle <args>`, "
    "`yk tool -C <ui|cli> <command>` (go, pnpm/npx), `yk check` (the gate for your changes; background it). "
    "Direct gradle, go and pnpm are blocked."
)
WRAPPERS = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*=.*|env|exec|nice|time|command|timeout|sudo|[0-9]+[smh]?)$")
NOISE = re.compile(r"^(-q|--quiet|--console=.*|--continue|--offline|--daemon|--no-daemon|--parallel|\d*>.*|<.*)$")
JS = {"pnpm", "npm", "npx", "pnpx", "yarn", "bun", "bunx"}
JS_BIN = {"tsc", "vitest", "eslint", "prettier", "vite", "playwright"}
SHELLS = {"bash", "sh", "zsh", "dash"}
RUN_APP = re.compile(r"[\s:](bootRun|nativeRun)\s")


def split_segments(cmd):
    """Splits on ; && || | & ( ) outside quotes."""
    out, cur, quote, i = [], [], "", 0
    while i < len(cmd):
        c = cmd[i]
        if quote:
            cur.append(c)
            if c == quote:
                quote = ""
        elif c in "'\"":
            quote = c
            cur.append(c)
        elif c in ";|&()\n":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    out.append("".join(cur))
    return out


def words_of(segment):
    try:
        return shlex.split(segment)
    except ValueError:
        return segment.split()


def rewrite(cmd, depth=0):
    """The yk command to run instead of `cmd`, or None when `cmd` may run as it is."""
    for segment in split_segments(cmd):
        words = words_of(segment.strip())
        while words and WRAPPERS.match(words[0]):
            words.pop(0)
        if not words:
            continue
        path = words[0]
        name = path.split("/")[-1]
        args = words[1:]
        if name in SHELLS and depth < 3 and "-c" in args[:3]:
            i = args.index("-c")
            if i + 1 < len(args):
                inner = rewrite(args[i + 1], depth + 1)
                if inner:
                    return inner
            continue
        is_gradle = name in ("gradlew", "gradle")
        is_go = name == "go" and bool(args) and args[0] in ("test", "vet", "build", "fmt")
        is_js = name in JS or (name in JS_BIN and "node_modules/.bin/" in path)
        if not (is_gradle or is_go or is_js):
            continue
        joined = " ".join(words)
        # running the app being developed is not a build or a check
        if RUN_APP.search(" " + (" ".join(args) if is_gradle else joined) + " "):
            return None
        if is_gradle:
            return "yk gradle " + (" ".join(shlex.quote(a) for a in args if not NOISE.match(a)) or "<tasks>")
        return ("yk tool -C cli " if is_go else "yk tool -C ui ") + joined
    return None


def git(root, *argv):
    r = subprocess.run(["git", "-C", root, *argv], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else ""


def yk_sum(yk):
    r = subprocess.run([yk, "sum"], capture_output=True, text=True)
    return r.stdout.strip()


def mod_installed():
    base = os.environ.get("CLAUDE_CONFIG_DIR") or str(Path.home() / ".claude")
    try:
        plugins = json.loads(Path(base, "plugins", "installed_plugins.json").read_text()).get("plugins", {})
    except (OSError, ValueError):
        return False
    return any(k.startswith("yukta-dogfood@") for k in plugins)


def emit(event, **fields):
    print(json.dumps({"hookSpecificOutput": {"hookEventName": event, **fields}}))


def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return ""


def main(event, data):
    if os.environ.get("YUKTA_DOGFOOD") == "off" or os.environ.get("YUKTA_HEAL_FIXER"):
        return
    root = git(os.getcwd(), "rev-parse", "--show-toplevel")
    yk = f"{root}/examples/11-dogfood/yk"
    if not root or not os.access(yk, os.X_OK):
        return
    state = Path(root, ".yukta-dev")

    if event == "session-start":
        sid = data.get("session_id") or ""
        state.mkdir(exist_ok=True)
        for old in state.glob("baseline-*"):
            if old.stat().st_mtime < time.time() - 7 * 86400:
                old.unlink(missing_ok=True)
        baseline = state / f"baseline-{sid}"
        if sid and not baseline.exists():  # a resumed session keeps its first baseline
            baseline.write_text(yk_sum(yk) + "\n")
        if data.get("source") != "compact":
            subprocess.run([yk, "up", "--background"], capture_output=True, timeout=60)
        env_file = os.environ.get("CLAUDE_ENV_FILE")
        if env_file:
            with open(env_file, "a") as f:
                f.write(f'export PATH="{root}/examples/11-dogfood:$PATH"\n')
        if mod_installed():
            return
        try:
            status = subprocess.run([yk, "status"], capture_output=True, text=True, timeout=5).stdout.split("\n")[0]
        except subprocess.TimeoutExpired:
            status = ""
        emit("SessionStart", additionalContext=f"Yukta dev server: {status or 'starting'}. {HINT} yk is examples/11-dogfood/yk.")

    elif event == "prompt":
        if not mod_installed():
            emit("UserPromptSubmit", additionalContext=HINT)

    elif event == "pre-bash":
        use = rewrite((data.get("tool_input") or {}).get("command") or "")
        if use:
            emit(
                "PreToolUse",
                permissionDecision="deny",
                permissionDecisionReason=(
                    f"Builds and tests of this repo run through Yukta (yukta skill). Run instead: {use}   "
                    "(yk is examples/11-dogfood/yk; the run prints a RESULT line). "
                    "To bypass for one session the user can set YUKTA_DOGFOOD=off."
                ),
            )

    elif event == "stop":
        if data.get("stop_hook_active") or not git(root, "status", "--porcelain", "--", *CODE):
            return
        sid = data.get("session_id") or ""
        total = yk_sum(yk)
        # nothing changed since the session began: the changes are someone else's
        if sid and read(state / f"baseline-{sid}") == total:
            return
        if read(state / "nudged") == total:
            return
        last = (read(state / "last-result").split() + ["", "", ""])[:3]
        if last[0] == total and last[1] == "SUCCESS" and last[2] == "check":
            return
        state.mkdir(exist_ok=True)
        (state / "nudged").write_text(total + "\n")
        print(
            json.dumps(
                {
                    "decision": "block",
                    "reason": "Uncommitted code changes: run the quality gate before finishing: yk check (yukta skill: start it "
                    "in the background; it heals what it can, so review its healed= changes with git diff). "
                    "YUKTA_DOGFOOD=off skips this.",
                }
            )
        )


if __name__ == "__main__":
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        payload = {}
    try:
        main(sys.argv[1] if len(sys.argv) > 1 else "", payload)
    except Exception as e:  # a hook must never break the session
        print(f"yk-hook: {e}", file=sys.stderr)
    sys.exit(0)
