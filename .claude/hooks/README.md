# Keeping CLAUDE.md honest: a Stop hook and skill telemetry

Both are opt-in and nothing here edits a CLAUDE.md. A CLAUDE.md or rule is reviewed like code: change it in a PR, with a reviewer.

## Stop hook: `suggest-claude-md.py`

After each turn it reads the new part of the session transcript and writes hints to
`~/.local/state/yukta-dev/claude-md-suggestions/<session>.md` (`YUKTA_CLAUDE_MD_REPORT_DIR` changes the folder):

- **Corrections**: your messages that start like one ("no,", "don't", "instead", "actually", "never", ...): should a CLAUDE.md or rule say it?
- **Blocks**: tool calls a hook or rule refused (`Blocked: ...`, denied permissions): do the instructions name the allowed way?
- **Edited areas**: for the files Claude edited, the module `CLAUDE.md` and the path-scoped rules (`.claude/rules/*.md` with `paths:`) that cover them,
  and the root `CLAUDE.md` when a build file changed: re-read them against the change.

You see one line (`Stop says: CLAUDE.md review: 2 correction(s), 1 block(s) ...`) when corrections or blocks were added; the file has the detail.
No model is called. The transcript is read incrementally, so a turn costs milliseconds (30 MB transcript: 0.2 s the first time, about 30 ms after).
It always exits 0 and never blocks Claude from stopping. It is off for headless fixers (`YUKTA_HEAL_FIXER`) and with `YUKTA_CLAUDE_MD_REVIEW=off`.

**Enable it** in your own `.claude/settings.local.json` (not committed, so it is opt-in), next to the `yukta-dogfood` mod's Stop hook, which still runs:

```json
{
  "hooks": {
    "Stop": [
      { "hooks": [ { "type": "command", "command": "python3 \"${CLAUDE_PROJECT_DIR}/.claude/hooks/suggest-claude-md.py\"", "timeout": 10 } ] }
    ]
  }
}
```

Then read the report at the end of a session and turn what is worth keeping into a PR that edits the CLAUDE.md or rule.
Test: `python3 .claude/hooks/test_suggest_claude_md.py`.

## Skill telemetry: find unused skills

Claude Code can export an OpenTelemetry log event, `claude_code.skill_activated`, each time a skill runs (Claude through the Skill tool, or you with
`/name`). Attributes: `skill.name`, `invocation_trigger` (`user-slash`, `claude-proactive`, `nested-skill`), `skill.source` (`projectSettings`,
`userSettings`, `plugin`, `bundled`). Without `OTEL_LOG_TOOL_DETAILS=1` the name of a user or plugin skill is the placeholder `custom_skill`.

**Turn it on** with environment variables in your shell, `~/.claude/settings.json` or managed settings. A repository `.claude/settings.json`
cannot set the exporter variables, so this is per person:

```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOG_TOOL_DETAILS=1          # real skill names instead of custom_skill
export OTEL_LOGS_EXPORTER=otlp          # to a collector you run: also OTEL_EXPORTER_OTLP_PROTOCOL and OTEL_EXPORTER_OTLP_ENDPOINT
export OTEL_LOGS_EXPORT_INTERVAL=5000   # ms, the default
```

Without a collector, `OTEL_LOGS_EXPORTER=console` prints each event to **standard output** (checked with `claude -p`), so redirect it:
`OTEL_LOGS_EXPORTER=console claude -p "/yukta say done" > session.log`.
The events carry `user.email` and account ids, so keep the raw logs private.

**Read it**: `python3 .claude/hooks/skill-usage.py session.log [more.log ...]` (or pipe the text in) counts activations per skill and trigger and lists
the project skills that have none:

```
    1  yukta  (user-slash)
project skills with no activation in this data: ide-index-mcp, playwright, shadcn, tests
```

**Retire with care**: collect a few weeks across the people who use the repo, because a skill nobody triggered in a short window may still matter.
A project skill with zero activations, no `/name` use and no mention in a CLAUDE.md or rule is a candidate: remove it in a PR (it also shortens the
skill list Claude reads every session). A skill that only ever runs `user-slash` is a command in practice; one that never runs
`claude-proactive` may have a description that does not start with the words a request would use (see the skills section of
`code.claude.com/docs/en/large-codebases`).
