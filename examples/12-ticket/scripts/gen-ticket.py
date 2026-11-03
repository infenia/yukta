#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Writes the workflows of the ticket pipeline into session.json (design 6.1 to 6.3, 9, 15.3, 15.5), the way
11-dogfood/scripts/gen-heal.py does. `yk ticket` adds them to the `dogfood` session, so they call its `tool` workflow.

  ticket        issue -> intake -> init -> claim -> route -> architect (validate-spec, 2 retries) -> test writer -> gate-tests -> [spec approval]
                -> plan comment -> attempt_round, recovered up to 3 times (revise: `escalate`; pause: a human packet and an ASK_INPUT) -> [merge gate]
                -> finish (draft PR) -> close
  attempt_round steps_round -> finalize
  steps_round   the steps whose depends_on are DONE, one `step` run each, in order (8 slots)
  step          one step, up to 4 attempts: begin -> implementer -> validate-step; a failure goes to `ladder decide`: retry, tier_up (one model tier up),
                heal (yk check --stage <stage> heals, then everything outside write_files is reverted), baseline (the stage also fails on origin/main),
                or an escalation (revise, human, pause: escalation.json)
  finalize      full checks -> reviewer -> verdict (a blocker or an unfaithful test overrides APPROVE)
  escalate      RevisionRequest -> architect revision -> spec v+1 -> validate-spec -> test writer (changed cases) -> gate-tests

Every node is a PROCESS_EXECUTOR running `ticketkit` (no Java); a Claude call is `ticketkit call ROLE`, which runs claude.sh --role ROLE. Claude calls share
the `claude` concurrency group; Gradle runs inside `yk check`, which queues in the `tool` group.

  gen-ticket.py          write session.json        gen-ticket.py --check   exit 1 when the committed session.json is stale (CI-style)
Edit this file, run it, commit session.json."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.environ.get("TICKET_SESSION") or os.path.join(HERE, "..", "session.json")
KIT = "__TICKET__/ticketkit"
S = lambda k: "${start.%s}" % k
RUN = S("run")
W = ["--worktree", S("worktree")]
START_KEYS = ("project", "run", "worktree", "claudeBin", "gateStep", "gateFinal", "stateDir", "healCmd", "baselineCmd", "runId", "events")


def edges(src, dst, *ports):
    return [{"source": src, "target": dst, "sourcePort": p} for p in ports] if ports else [{"source": src, "target": dst}]


def pe(node_id, args, timeout=300, json_out=False, claude=False, routed=True):
    env = {"YK_CLAUDE_BIN": S("claudeBin"), "TICKET_GATE_STEP": S("gateStep"), "TICKET_GATE_FINAL": S("gateFinal"), "YUKTA_DEV_STATE_DIR": S("stateDir"),
           "TICKET_HEAL_CMD": S("healCmd"), "TICKET_BASELINE_CMD": S("baselineCmd")}
    cfg = {"command": [KIT, *args], "workingDir": S("project"), "env": env, "timeout": timeout, "timeoutSeconds": timeout, "failureMode": "CONTINUE"}
    if routed:
        cfg["routeByExitCode"] = True
    if json_out:
        cfg["outputFormat"] = "JSON"
    if claude:
        cfg["engine"] = {"concurrencyGroup": "claude"}
    return {"nodeId": node_id, "type": "PROCESS_EXECUTOR", "config": cfg}


def terminal(node_id, reason):
    return {"nodeId": node_id, "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": reason}}


def ok(node_id="ok"):
    return {"nodeId": node_id, "type": "CONSOLE_TERMINAL", "config": {"displayOutput": True}}


def select(node_id, key, cases, default):
    return {"nodeId": node_id, "type": "BRANCH", "config": {"mode": "SELECT_KEY", "selector": "payload['output']['%s']" % key, "cases": cases, "defaultPort": default}}


def ask(node_id, prompt, ttl=86400):
    return {"nodeId": node_id, "type": "ASK_INPUT", "config": {"prompt": prompt, "kind": "APPROVAL", "risk": "MEDIUM", "ttlSeconds": ttl, "onTimeout": "FAIL"}}


def rejected(node_id):
    return {"nodeId": node_id, "type": "BRANCH", "config": {"mode": "EXPRESSION", "cases": {"metadata['input.decision'] == 'reject'": "reject"}, "defaultPort": "approve"}}


def sub(node_id, wf, keys=START_KEYS, **extra):
    payload = {k: S(k) for k in keys} | extra
    return {"nodeId": node_id, "type": "SUB_WORKFLOW", "config": {"workflowId": wf, "timeoutSeconds": 21600, "routeByOutcome": True, "payload": payload}}


def dead_end(node_id, why):
    """The issue goes back to Ready with a comment (design 15.9 step 7); best effort, the run ends FAILED at <node_id>.failed either way."""
    return pe(node_id + ".dead", ["github", "dead-end", RUN, "--stage", node_id, "--reason", why], 120, routed=False)


class G:
    """Collects nodes and edges; fail(node, why, dead) wires a node's failure port to a terminal (through a dead-end when asked)."""

    def __init__(self):
        self.nodes, self.edges = [], []

    def add(self, *nodes):
        self.nodes += nodes

    def link(self, src, dst, *ports):
        self.edges += edges(src, dst, *ports)

    def fail(self, nid, why, dead=False, port="failure"):
        self.nodes.append(terminal(nid + ".failed", why))
        if dead:
            self.nodes.append(dead_end(nid, why))
            self.link(nid, nid + ".dead", port)
            self.link(nid + ".dead", nid + ".failed")
        else:
            self.link(nid, nid + ".failed", port)

    def chain(self, items, last, dead=False):
        """items: [(node, failure_reason)]; each success goes to the next node, the last to `last`."""
        for k, (n, why) in enumerate(items):
            self.add(n)
            self.link(n["nodeId"], items[k + 1][0]["nodeId"] if k + 1 < len(items) else last, "success")
            self.fail(n["nodeId"], why, dead)

    def workflow(self, wf_id, description):
        return {"workflowId": wf_id, "description": description, "nodes": [{"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {}}] + self.nodes, "edges": self.edges}


# ---------------------------------------------------------------- step: up to 4 attempts (3, +1 after a revision)
ATTEMPTS = 4
st = G()
st.add(terminal("escalate", "escalate: the step needs a revision, a human or a pause (escalation.json says which)"), ok("ok"))
commit = pe("commit", ["flow", "commit-step", RUN, S("step"), *W], 120)
st.add(commit)
st.link("commit", "ok", "success")
st.fail("commit", "the step could not be committed")
for k in range(1, ATTEMPTS + 1):
    b, im, rs, va, de, ac = (f"{n}{k}" for n in ("begin", "implement", "result", "validate", "decide", "action"))
    st.add(pe(b, ["flow", "step-begin", RUN, S("step"), "--attempt", "next", *W], 300))
    st.add(pe(im, ["call", "implementer", RUN, "--step", S("step"), "--attempt", "cur", *W], 2400, json_out=True, claude=True))
    st.add(select(rs, "status", {"DONE": "done"}, "blocked"))
    st.add(pe(va, ["flow", "validate-step", RUN, S("step"), "--attempt", "cur", *W], 3700))
    st.add(pe(de, ["ladder", "decide", RUN, S("step"), *W], 120, json_out=True))
    st.add(select(ac, "action", {"retry": "go", "tier_up": "go", "heal": "heal", "baseline": "baseline"}, "escalate"))
    st.link(b, im, "success"); st.fail(b, "the step base or packet failed")
    st.link(im, rs, "success"); st.fail(im, "the implementer failed or its answer stayed invalid after one retry")
    st.link(rs, va, "done"); st.link(rs, de, "blocked")
    st.link(va, "commit", "success"); st.link(va, de, "failure")
    st.link(de, ac, "success"); st.fail(de, "the ladder could not decide")
    st.link(ac, f"begin{k + 1}" if k < ATTEMPTS else "escalate", "go")
    st.link(ac, "commit", "baseline"); st.link(ac, "escalate", "escalate")
    # heal: yk check heals the stage, anything outside write_files is reverted, the step is validated again; still failing -> escalation
    h, vh, dh = f"heal{k}", f"validate_heal{k}", f"decide_heal{k}"
    st.add(pe(h, ["ladder", "heal", RUN, S("step"), *W], 3700, json_out=True))
    st.add(pe(vh, ["flow", "validate-step", RUN, S("step"), "--attempt", "cur", *W], 3700))
    st.add(pe(dh, ["ladder", "decide", RUN, S("step"), "--after-heal", *W], 120, json_out=True))
    st.link(ac, h, "heal"); st.link(h, vh, "success"); st.fail(h, "heal could not run")
    st.link(vh, "commit", "success"); st.link(vh, dh, "failure"); st.link(dh, "escalate", "success"); st.fail(dh, "the ladder could not decide")
STEP_WF = st.workflow("step", "One step of a ticket: up to four attempts with the retry ladder, heal for mechanical failures, escalation.")
STEP_WF["edges"] = [e for e in STEP_WF["edges"]] + edges("trigger", "begin1")

# ---------------------------------------------------------------- steps_round: every step whose depends_on are DONE, in order (8 slots, the sizing lint caps a spec at 8)
SLOTS = 8
sr = G()
sr.add(ok("ok"))
for k in range(1, SLOTS + 1):
    nx, more, run = f"next{k}", f"more{k}", f"run{k}"
    sr.add(pe(nx, ["ladder", "next-step", RUN], 60, json_out=True), select(more, "more", {"true": "go"}, "none"),
           sub(run, "step", step="${payload.output.step}"))
    sr.link(nx, more, "success"); sr.fail(nx, "next-step failed")
    sr.link(more, run, "go"); sr.link(more, "ok", "none")
    sr.link(run, f"next{k + 1}" if k < SLOTS else "ok", "success"); sr.fail(run, "a step ended without DONE (escalation.json says what to do)")
STEPS_WF = sr.workflow("steps_round", "Run the steps whose depends_on are DONE, one step workflow each, in order.")
STEPS_WF["edges"] += edges("trigger", "next1")

# ---------------------------------------------------------------- finalize: full checks, reviewer, verdict
fz = G()
fz.add(ok("ok"), terminal("changes.failed", "the reviewer requested changes (escalation.json)"), terminal("full_checks.failed", "the final gate failed"))
fz.add(pe("full_checks", ["flow", "full-checks", RUN, *W], 3700, ), pe("final_fail", ["ladder", "final-fail", RUN], 60, routed=False))
fz.link("full_checks", "review", "success"); fz.link("full_checks", "final_fail", "failure"); fz.link("final_fail", "full_checks.failed")
fz.add(pe("review", ["call", "reviewer", RUN, "--step", "S1", *W], 2400, json_out=True, claude=True))
fz.fail("review", "the reviewer failed")
fz.link("review", "verdict", "success")
fz.add(pe("verdict", ["ladder", "review-verdict", RUN], 60, json_out=True), select("verdict?", "verdict", {"APPROVE": "approved"}, "changes"))
fz.link("verdict", "verdict?", "success"); fz.fail("verdict", "the verdict could not be computed")
fz.link("verdict?", "ok", "approved"); fz.link("verdict?", "changes.failed", "changes")
FINAL_WF = fz.workflow("finalize", "The final gate, the fresh-context reviewer and a verdict code can override.")
FINAL_WF["edges"] += edges("trigger", "full_checks")

# ---------------------------------------------------------------- attempt_round: the steps, then the finish line
ar = G()
ar.add(sub("steps", "steps_round"), sub("finalize", "finalize"), ok("ok"))
ar.link("steps", "finalize", "success"); ar.fail("steps", "a step did not finish")
ar.link("finalize", "ok", "success"); ar.fail("finalize", "the final gate or the review did not pass")
ROUND_WF = ar.workflow("attempt_round", "All steps, the final gate and the review.")
ROUND_WF["edges"] += edges("trigger", "steps")

# ---------------------------------------------------------------- escalate: the architect revises the spec (design 6.3)
es = G()
es.add(ok("ok"))
es.chain([(pe("build_revision", ["ladder", "build-revision", RUN], 60), "the revision request could not be built"),
          (pe("architect_revise", ["call", "architect_revise", RUN, *W], 2400, json_out=True, claude=True), "the architect's revision stayed invalid"),
          (pe("apply_revision", ["ladder", "apply-revision", RUN], 60), "the revision touches completed steps or is not applicable"),
          (pe("validate_spec", ["validate-spec", RUN, *W], 300), "the revised spec is not valid"),
          (pe("test_writer", ["call", "test_writer", RUN, *W], 2400, json_out=True, claude=True), "the test writer failed on the changed cases"),
          (pe("gate_tests", ["gate-tests", RUN, *W], 3700), "the changed tests did not pass the red check"),
          (pe("commit_tests", ["flow", "commit-tests", RUN, *W], 120), "the tests could not be committed")], "ok")
ESC_WF = es.workflow("escalate", "Architect revision: spec v+1, changed cases through the test writer and the gate.")
ESC_WF["edges"] += edges("trigger", "build_revision")

# ---------------------------------------------------------------- ticket
t = G()
t.add(ok("done"))
t.chain([(pe("intake", ["github", "intake", S("issue")], 120), "the issue is not eligible (not open, assigned to you and Ready, or no acceptance criteria) or asks for something forbidden")], "init")
t.chain([(pe("init", ["init", S("issue"), "--run-id", S("runId"), "--root", S("project")], 300), "init failed or the issue is skipped (the branch or worktree exists)"),
         (pe("claim", ["github", "claim", S("issue")], 120), "the issue could not be moved to In progress")], "route")
t.chain([(pe("route", ["route", RUN], 120), "route failed")], "architect1", dead=True)
# the architect, with validate-spec errors fed back, twice at most
for k in (1, 2, 3):
    t.add(pe(f"architect{k}", ["call", "architect", RUN, *W], 2400, json_out=True, claude=True), pe(f"validate_spec{k}", ["validate-spec", RUN, *W], 300))
    t.link(f"architect{k}", f"validate_spec{k}", "success"); t.fail(f"architect{k}", "the architect failed, asked for clarification or stayed invalid", dead=True)
    t.link(f"validate_spec{k}", "test_writer", "success")
    if k < 3:
        t.link(f"validate_spec{k}", f"architect{k + 1}", "failure")
    else:
        t.fail("validate_spec3", "the spec is still invalid after two retries", dead=True)
t.chain([(pe("test_writer", ["call", "test_writer", RUN, *W], 2400, json_out=True, claude=True), "the test writer failed"),
         (pe("gate_tests", ["gate-tests", RUN, *W], 3700), "the tests did not pass the red check"),
         (pe("commit_tests", ["flow", "commit-tests", RUN, *W], 120), "the tests could not be committed")], "need_spec", dead=True)
# approvals: a high-risk spec waits for a person (yk approve | reject)
t.add(pe("need_spec", ["ladder", "need-approval", RUN, "spec"], 60, json_out=True), select("spec_ask?", "ask", {"true": "ask"}, "skip"),
      ask("approve_spec", "Approve the spec of run ${start.runId} (high risk)? spec/spec.json in ${start.run}"), rejected("spec_decision?"), terminal("spec_rejected", "a person rejected the spec"))
t.link("need_spec", "spec_ask?", "success"); t.fail("need_spec", "approval policy failed", dead=True)
t.link("spec_ask?", "approve_spec", "ask"); t.link("spec_ask?", "plan_comment", "skip")
t.link("approve_spec", "spec_decision?"); t.link("spec_decision?", "spec_rejected", "reject"); t.link("spec_decision?", "plan_comment", "approve")
t.chain([(pe("plan_comment", ["github", "plan-comment", RUN], 120), "the plan could not be posted on the issue")], "round1", dead=True)
# rounds: a failed round is recovered by a revision or a pause, 3 times
ROUNDS = 4
for k in range(1, ROUNDS + 1):
    r, rf = f"round{k}", f"route_failure{k}"
    t.add(sub(r, "attempt_round"))
    t.link(r, "need_merge", "success")
    if k == ROUNDS:
        t.fail(r, "the run did not finish after three recoveries", dead=True)
        continue
    t.link(r, rf, "failure")
    a, e, pp, aa, ap, ack = f"action{k}", f"escalate{k}", f"pause_packet{k}", f"ask_pause{k}", f"pause_decision{k}", f"ack_pause{k}"
    t.add(pe(rf, ["ladder", "route-failure", RUN], 60, json_out=True), select(a, "action", {"revise": "revise", "pause": "pause"}, "human"),
          sub(e, "escalate"), pe(pp, ["ladder", "pause-packet", RUN], 60), ask(aa, "Run ${start.runId} is paused over a budget; human-packet.md is in ${start.run}. Approve to continue with doubled budgets."),
          rejected(ap), pe(ack, ["ladder", "ack-pause", RUN], 60), terminal(f"paused_rejected{k}", "a person stopped the paused run"))
    t.link(rf, a, "success"); t.fail(rf, "the failure could not be routed", dead=True)
    t.link(a, e, "revise"); t.link(a, pp, "pause"); t.link(a, f"human{k}", "human")
    t.add(pe(f"human{k}", ["github", "dead-end", RUN, "--stage", f"round{k}", "--reason", "a person is needed (escalation.json)"], 120, routed=False), terminal(f"human{k}.failed", "a person is needed (licenses, security, a second infra error or the revision budget)"))
    t.link(f"human{k}", f"human{k}.failed")
    t.link(e, f"round{k + 1}", "success"); t.fail(e, "the architect's revision did not produce a valid spec", dead=True)
    t.link(pp, aa, "success"); t.fail(pp, "the human packet could not be written")
    t.link(aa, ap); t.link(ap, f"paused_rejected{k}", "reject"); t.link(ap, ack, "approve"); t.link(ack, f"round{k + 1}", "success"); t.fail(ack, "the pause could not be acknowledged")
# merge gate by policy, then the draft PR
t.add(pe("need_merge", ["ladder", "need-approval", RUN, "merge"], 60, json_out=True), select("merge_ask?", "ask", {"true": "ask"}, "skip"),
      ask("approve_merge", "Open the PR for run ${start.runId}? The change is on its branch, reviewed and green."), rejected("merge_decision?"), terminal("merge_rejected", "a person declined the PR"))
t.link("need_merge", "merge_ask?", "success"); t.fail("need_merge", "approval policy failed")
t.link("merge_ask?", "approve_merge", "ask"); t.link("merge_ask?", "finish", "skip")
t.link("approve_merge", "merge_decision?"); t.link("merge_decision?", "merge_rejected", "reject"); t.link("merge_decision?", "finish", "approve")
t.chain([(pe("finish", ["github", "finish", RUN, *W], 600), "the draft PR could not be opened"),
         (pe("close", ["flow", "close", RUN, "--events", S("events")], 60), "close failed")], "done")
t.edges += edges("trigger", "intake")
TICKET_WF = t.workflow("ticket", "Turn a GitHub issue with acceptance criteria into a reviewed draft PR: architect, tests, steps with a retry ladder, review.")

def check(wfs):
    for wid, wf in wfs.items():
        ids = [n["nodeId"] for n in wf["nodes"]]
        assert len(ids) == len(set(ids)), (wid, sorted({i for i in ids if ids.count(i) > 1}))
        for e in wf["edges"]:
            assert e["source"] in ids and e["target"] in ids, (wid, e)
        for n in wf["nodes"]:
            if n["type"] == "SUB_WORKFLOW":
                assert n["config"]["workflowId"] in wfs, (wid, n["nodeId"])


WORKFLOWS = {"ticket": TICKET_WF, "attempt_round": ROUND_WF, "steps_round": STEPS_WF, "step": STEP_WF, "finalize": FINAL_WF, "escalate": ESC_WF}
check(WORKFLOWS)
out = json.dumps({"workflows": WORKFLOWS}, indent=2) + "\n"
if "--check" in sys.argv:
    cur = open(PATH).read() if os.path.exists(PATH) else ""
    if cur != out:
        sys.exit("session.json is stale: run examples/12-ticket/scripts/gen-ticket.py and commit it")
    print("session.json is current")
else:
    with open(PATH, "w") as f:
        f.write(out)
    print(", ".join(f"{k} {len(v['nodes'])}" for k, v in WORKFLOWS.items()), "nodes")
