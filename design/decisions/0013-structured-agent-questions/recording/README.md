# Recording the agent questions

Scripts that recorded the fixtures of [ADR 0013](../../0013-structured-agent-questions.md) (#2706) under
`plugins/processors/agent-session/src/test/resources/agent-sessions/`: `claude-code/ask-user-question-*.jsonl`
and `antigravity-acp/elicitation-*.jsonl`. They talk to the real CLI, so they need a logged-in account and
cost a few cents; no test runs them. Run from a scratch directory outside the repository:

```bash
# claude 2.1.332; SCENARIO: single | multi | other | several | plain | deny
python3 -I record_claude_questions.py SCENARIO raw.jsonl /scratch/rec/work
python3 -I sanitize_claude.py raw.jsonl ask-user-question-SCENARIO.jsonl

# agy 1.5.10 (~/.gemini/bin/agy); SCENARIO: single | multi | other | decline | nocap
python3 -I record_acp_question.py SCENARIO raw.jsonl /scratch/rec/agy -- ~/.gemini/bin/agy acp
python3 -I sanitize_acp.py raw.jsonl elicitation-SCENARIO.jsonl
```

The sanitizers expect the working directory under `/tmp/claude-1000/…/rec/`; adapt the pattern for another
path. `sanitize_claude.py` applies the rules of ADR 0004's `sanitize.py` and also drops every `system` line but
`init` (2.1.332 prints `ui_*` lines and the output of the recording user's own hooks). `sanitize_acp.py`
replaces the working directory and the session id and keeps only the first model of `session/new`. Nothing
else is changed: the `503` retries Antigravity printed in some runs are in the fixtures as they happened.

The terminal (TUI) screens in the ADR were captured with `tmux` (`new -d -x 120 -y 40`, `send-keys`,
`capture-pane -p`) from `claude --model haiku` 2.1.332; the status line below the prompt box, which is the
recording user's own configuration, is left out.
