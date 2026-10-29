#!/usr/bin/env python3
"""Writes the workflows of healing into session.json, as designed in DESIGN.html (library: claude, attempt, heal):

  claude     L0  one headless Claude Code session (scripts/claude.sh)
  attempt    L1  one repair attempt: prepare -> claude -> claimed -> format -> judge (the guard) -> recheck -> report
  heal       L1  free automatic fix, then the System One triage picks a level; each level of heal.json has a ladder of rungs
                 (model/effort, one attempt each): a failed rung hands over to the next, the end of the ladder gives up

The efforts (budget, time box), the triage levels and the triage model come from heal.json. Gradle is a `tool` call like any
other command, so the failed stage's command, its formatter and its free fix are plain shell commands of the start payload.
`check` (scripts/gen-check.py) calls `heal` once per failed stage. Run after editing heal.json, then commit session.json."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(HERE, "..", "session.json")
POLICY = json.load(open(os.path.join(HERE, "..", "heal.json")))
HEAL = "__DOGFOOD__/scripts/yukta-heal.sh"
CLAUDE = "__DOGFOOD__/scripts/claude.sh"
EFFORTS = {e: {"budget": c["budget"], "timeout": c["timeoutSeconds"]} for e, c in POLICY["efforts"].items()}
LEVELS = POLICY["triage"]["levels"]
SINGLE = POLICY.get("singleAttempt", [])
FIX_SCHEMA = '{"type":"object","properties":{"fixed":{"type":"boolean"},"summary":{"type":"string"}},"required":["fixed","summary"]}'

# what every layer passes on to the next: heal -> attempt
KEYS = ["project", "stage", "command", "format", "modules", "healDir", "events", "models", "timeout",
        "rules", "claudeBin", "budgetCheck"]


def S(k):
    return "${start.%s}" % k


def passed(keys):
    return {k: S(k) for k in keys}


def edges(src, dst, *ports):
    return [{"source": src, "target": dst, "sourcePort": p} for p in ports] if ports else [{"source": src, "target": dst}]


ROUTE = {"engine": {"onError": "ROUTE"}}


def tool_call(command, label, **extra):
    c = {"workflowId": "tool", "timeoutSeconds": 3720, "routeByOutcome": True,
         "payload": {"project": S("project"), "command": command, "timeout": S("timeout"), "label": label}}
    c.update(extra)
    return c


def pe(node_id, action, env=None, stdin=False, json_out=False, timeout=120, routed=False, env_in=False):
    cfg = {"command": [HEAL, action], "workingDir": S("project"), "env": env or {}, "timeout": timeout, "timeoutSeconds": timeout}
    if stdin:
        cfg["inputMode"] = "STDIN"
    if env_in:  # the dead letter's failure.message arrives as YUKTA_METADATA_FAILURE_MESSAGE, the brief as YUKTA_PAYLOAD
        cfg["inputMode"] = "ENV"
    if json_out:
        cfg["outputFormat"] = "JSON"
    cfg.update({"failureMode": "CONTINUE", "routeByExitCode": True} if routed else {"failureMode": "CONTINUE"})
    return {"nodeId": node_id, "type": "PROCESS_EXECUTOR", "config": cfg}


def heal_env(**extra):
    env = {"YUKTA_HEAL_DIR": S("healDir"), "YUKTA_STAGE": S("stage"), "YUKTA_MODULE": S("modules"), "YUKTA_HEAL_EVENTS": S("events")}
    env.update(extra)
    return env


def from_result(path, on_error, on_crash):
    """An expression that reads the result of a SUB_WORKFLOW safely: results may be missing (the message came through the error
    port, so it is the node's own input) or empty (the child execution crashed)."""
    return ("payload['results'] == null ? %s : (payload['results'].size() > 0 ? payload['results'][0]%s : %s)"
            % (on_error, path, on_crash))


def carry(path):
    return {"mode": "PROJECTION", "mapping": {
        "problems": from_result(path + "['problems']", "payload['problems']", "'The previous attempt did not finish: ' + payload['reason']"),
        "previous": from_result(path + "['summary']", "'The previous attempt ran out of time.'", "''"),
        "changed": from_result(path + "['changed']", "true", "true"),
        "same": from_result(path + "['same']", "false", "false"),
        "stage": from_result(path + "['stage']", "''", "''")}}


# ---------------------------------------------------------------- claude (L0)
CLAUDE_WF = {"workflowId": "claude", "description": "One headless Claude Code session in a project; returns its JSON answer and what it cost.",
  "nodes": [
    {"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {"variables": {"prompt": S("prompt")}}},
    {"nodeId": "run", "type": "PROCESS_EXECUTOR", "config": {
        "command": [CLAUDE], "workingDir": S("project"), "inputMode": "STDIN", "outputFormat": "JSON",
        "env": {"YK_MODEL": S("model"), "YK_EFFORT": S("effort"), "YK_BUDGET": S("budget"), "YK_RULES": S("rules"), "YK_SCHEMA": S("schema"),
                "YK_SESSION": S("sessionId"), "YK_CLAUDE_BIN": S("claudeBin")},
        "timeout": "${start.timeout:int}", "failureMode": "CONTINUE", "routeByExitCode": True, "engine": {"concurrencyGroup": "claude"}}},
    {"nodeId": "ok", "type": "CONSOLE_TERMINAL", "config": {}},
    {"nodeId": "failed", "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": "the claude session failed, ran out of budget or ran out of time"}}],
  "edges": edges("trigger", "run") + edges("run", "ok", "success") + edges("run", "failed", "failure")}

# ---------------------------------------------------------------- attempt (L1)
claude_call = {"workflowId": "claude", "timeoutSeconds": 7200, "routeByOutcome": True, "engine": {"onError": "ROUTE"},
  "payload": {"project": S("project"), "prompt": "${payload.output.prompt}", "model": S("model"), "effort": S("effort"), "budget": S("budget"),
              "timeout": S("claudeTimeout"), "rules": S("rules"), "schema": FIX_SCHEMA, "sessionId": "-",
              "claudeBin": S("claudeBin")}}
a_env = {"YUKTA_MODEL": S("model"), "YUKTA_EFFORT": S("effort"), "YUKTA_COMMAND": S("command")}
ATTEMPT_WF = {"workflowId": "attempt", "description": "One repair attempt: one Claude session at one model and effort, judged by re-running the failed stage.",
  "nodes": [
    {"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {"variables": {"problems": S("problems"), "previous": S("previous")}}},
    pe("prepare", "prepare", heal_env(**a_env, YUKTA_MODELS=S("models"), YUKTA_BUDGET_CHECK=S("budgetCheck"),
                                      YUKTA_HEAL_TIMEOUT=S("claudeTimeout")),
       stdin=True, json_out=True, routed=True),
    {"nodeId": "claude", "type": "SUB_WORKFLOW", "config": claude_call},
    pe("claimed", "claimed", heal_env(**a_env), stdin=True, timeout=60),
    {"nodeId": "format", "type": "SUB_WORKFLOW", "config": {**tool_call(S("format"), "format"), **ROUTE}},
    pe("judge", "judge", heal_env(**a_env), routed=True),
    {"nodeId": "recheck", "type": "SUB_WORKFLOW", "config": {**tool_call(S("command"), S("stage")), **ROUTE}},
    pe("report", "report", heal_env(**a_env, YUKTA_PROBLEMS="${start.problems}"), stdin=True, json_out=True),
    {"nodeId": "fixed?", "type": "BRANCH", "config": {"mode": "SELECT_KEY", "selector": "payload['output']['fixed']", "cases": {"true": "fixed"}, "defaultPort": "notfixed"}},
    {"nodeId": "ok", "type": "CONSOLE_TERMINAL", "config": {}},
    {"nodeId": "failed", "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": "the attempt did not fix the stage"}}],
  "edges": (edges("trigger", "prepare") + edges("prepare", "claude", "success") + edges("prepare", "report", "failure")
            + edges("claude", "claimed", "success", "failure", "error") + edges("claimed", "format")
            + edges("format", "judge", "success", "failure", "error") + edges("judge", "recheck", "success") + edges("judge", "report", "failure")
            + edges("recheck", "report", "success", "failure", "error") + edges("report", "fixed?") + edges("fixed?", "ok", "fixed") + edges("fixed?", "failed", "notfixed"))}

def attempt_call(m, e):
    return {"workflowId": "attempt", "timeoutSeconds": 7200, "routeByOutcome": True, "engine": {"onError": "ROUTE"},
            "payload": {**passed(KEYS), "model": m, "effort": e, "budget": EFFORTS[e]["budget"], "claudeTimeout": EFFORTS[e]["timeout"],
                        "problems": "${payload.problems}", "previous": "${payload.previous}"}}


# ---------------------------------------------------------------- heal (L1)
def rid(m, e):
    return "fix-%s-%s" % (m, e)


# Each level has a ladder of rungs "model/effort", one attempt each (no effort climbing): the first is where the triage starts, a failed
# rung hands over to the next one, and the end of the ladder gives up. Rungs shared by levels share a node (and must share the rest of the ladder).
rungs, nxt = {}, {}
for lvl, c in LEVELS.items():
    steps = [tuple(x.split("/")) for x in c["ladder"]]
    for k, st in enumerate(steps):
        rungs.setdefault(st, {"nodeId": rid(*st), "type": "SUB_WORKFLOW", "config": attempt_call(*st)})
        after = steps[k + 1] if k + 1 < len(steps) else None
        assert nxt.setdefault(st, after) == after, "rung %s/%s has two different successors" % st
starts = {lvl: rid(*c["ladder"][0].split("/")) for lvl, c in LEVELS.items()}
avg = starts[POLICY["triage"]["default"]]
nodes = [
    {"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {"variables": {"stdout": S("stdout"), "heal": S("heal"), "auto": S("auto")}}},
    {"nodeId": "heal?", "type": "BRANCH", "config": {"mode": "SELECT_KEY", "selector": "payload['heal']", "cases": {"off": "off"}, "defaultPort": "on"}},
    {"nodeId": "disabled", "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": "healing is off (yk check --no-heal)"}},
    {"nodeId": "auto?", "type": "BRANCH", "config": {"mode": "EXPRESSION", "cases": {"payload['auto'] != null and payload['auto'] != '' and payload['auto'] != '-'": "auto"}, "defaultPort": "model"}},
    {"nodeId": "auto-fix", "type": "SUB_WORKFLOW", "config": {**tool_call(S("auto"), "auto-fix"), **ROUTE}},
    {"nodeId": "auto-check", "type": "SUB_WORKFLOW", "config": {**tool_call(S("command"), S("stage")), **ROUTE}},
    pe("auto-record", "record-auto", heal_env(), timeout=60),
    pe("brief", "brief", heal_env(YUKTA_COMMAND=S("command"), YUKTA_TRIAGE=S("triage")), stdin=True, json_out=True),
    {"nodeId": "first", "type": "MAPPER", "config": {"mode": "PROJECTION", "mapping": {
        "brief": "payload['output']['brief']", "problems": "payload['output']['problems']", "previous": "''", "triage": "payload['output']['triage']"}}},
    {"nodeId": "triage?", "type": "BRANCH", "config": {"mode": "SELECT_KEY", "selector": "payload['triage']", "cases": {"off": "off"}, "defaultPort": "on"}},
    {"nodeId": "triage", "type": "CLASSIFY", "config": {
        "model": S("triageModel"), "baseUrl": S("triageUrl"), "state": "payload['brief']",
        "question": POLICY["triage"].get("question", "How hard is it to fix this failed check?"),
        "labels": {k: v["describe"] for k, v in LEVELS.items()},
        "minProbability": POLICY["triage"]["minProbability"], "routeByLabel": True, "timeout": POLICY["triage"].get("timeoutSeconds") or 55,
        "engine": {"onError": "ROUTE"}}},
]
e = []
e += edges("trigger", "heal?") + edges("heal?", "disabled", "off") + edges("heal?", "auto?", "on")
e += edges("auto?", "auto-fix", "auto") + edges("auto?", "brief", "model")
e += edges("auto-fix", "auto-check", "success", "failure", "error") + edges("auto-check", "auto-record", "success")
e += edges("auto-check", "brief", "failure", "error") + edges("auto-record", "done")
e += edges("brief", "first") + edges("first", "triage?") + edges("triage?", "triage", "on") + edges("triage?", avg, "off")
# every port of the triage also feeds a log node (a dead end): its event is what heal-report.py reads to judge the triage
TRIAGE_PORTS = list(LEVELS) + ["uncertain", "error"]
for port in TRIAGE_PORTS:
    start_rung = starts[port] if port in LEVELS else avg
    nodes.append(pe("triage-log." + port, "triage-log", heal_env(YUKTA_TRIAGE_ROUTE=port, YUKTA_TRIAGE_START=start_rung.replace("fix-", "", 1).replace("-", "/"),
                                                                  YUKTA_TRIAGE_MODEL=S("triageModel")), stdin=port != "error", env_in=port == "error", timeout=60))
    e += edges("triage", "triage-log." + port, port) + edges("triage-log." + port, "logged")
e += edges("triage", starts["easy"], "easy") + edges("triage", avg, "average", "uncertain", "error") + edges("triage", starts["hard"], "hard")
for st, after in nxt.items():
    e += edges(rid(*st), "done", "success")
    if after:
        c = "carry." + rid(*after)
        if not any(n["nodeId"] == c for n in nodes):
            nodes.append({"nodeId": c, "type": "MAPPER", "config": carry("['output']")})
            # an attempt that changed nothing gains nothing from a stronger model asking the same question: give up. So does a second
            # attempt on a stage in heal.json singleAttempt, which an edit to the code often cannot fix (a licence header, a docs link)
            # nor does one that failed exactly as the attempt before it did (no progress, `same` of the report step)
            stop = "payload['changed'] == false or payload['same'] == true" + (" or {%s}.contains(payload['stage'])" % ",".join("'%s'" % x for x in SINGLE) if SINGLE else "")
            nodes.append({"nodeId": c + "?", "type": "BRANCH", "config": {"mode": "EXPRESSION", "cases": {stop: "stop"}, "defaultPort": "go"}})
            e += edges(c, c + "?") + edges(c + "?", rid(*after), "go") + edges(c + "?", "giveup", "stop")
        e += edges(rid(*st), c, "failure", "error")
    else:
        e += edges(rid(*st), "giveup", "failure", "error")
nodes += list(rungs.values())
nodes += [{"nodeId": "done", "type": "CONSOLE_TERMINAL", "config": {}},
          {"nodeId": "logged", "type": "CONSOLE_TERMINAL", "config": {}},  # the end of the log branches; they have no result to give
          {"nodeId": "giveup", "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": "every model failed at every effort"}}]
HEAL_WF = {"workflowId": "heal", "description": "Repair a failed stage: the free automatic fix first, then triage and the model ladder, escalating on failure.",
           "nodes": nodes, "edges": e}

d = json.load(open(PATH))
d["workflows"].pop("fix-from", None)
for wf in (CLAUDE_WF, ATTEMPT_WF, HEAL_WF):
    d["workflows"][wf["workflowId"]] = wf
    print(wf["workflowId"], len(wf["nodes"]), "nodes")
with open(PATH, "w") as f:
    json.dump(d, f, indent=2)
    f.write("\n")
