# Moved out of scripts/_lib.sh during the rebuild: the issue and epic workflow generators and dev_session
# ---- The issue workflows ----

# issue_variant IMPL_PAUSE PR_PAUSE: the suffix of the workflows without the checkpoints that are off (0)
issue_variant() {
  if [[ "$1" == 1 && "$2" == 1 ]]; then echo ""
  elif [[ "$1" == 0 && "$2" == 1 ]]; then echo "-no-impl-pause"
  elif [[ "$1" == 1 && "$2" == 0 ]]; then echo "-no-pr-pause"
  else echo "-no-pauses"; fi
}

# issue_workflows ISSUESH IMPL_PAUSE PR_PAUSE: prints {"workflows": {...}} with run-issue and implement-issue
# (suffixed by issue_variant). Everything varying per run comes from the start payload: issue, repo (the
# main checkout), worktree.
#
#   run-issue        the outer run: implement-issue as a sub-workflow; however it ends badly (a failing node,
#                    a rejected or unanswered checkpoint, a failing check) release gives the issue back to Ready
#                    and the run ends ESCALATE, the worktree kept
#   implement-issue  claim -> worktree -> brief -> [before-implement] -> nav
#                    (a code-navigation index of the worktree) -> implement -> check (the self-healing gate: triage,
#                    fresh fixers) -> [before-pr] -> pr -> done
issue_workflows() {
  local suffix
  suffix="$(issue_variant "$2" "$3")"
  jq -n --arg sh "$1" --arg impl "$2" --arg pr "$3" --arg suffix "$suffix" '
    def env: {YUKTA_ISSUE: "${start.issue}", YUKTA_REPO_DIR: "${start.repo}", YUKTA_WORKTREE: "${start.worktree}"}
             + (if $ENV.YUKTA_CLAUDE_BIN then {YUKTA_CLAUDE_BIN: $ENV.YUKTA_CLAUDE_BIN} else {} end)
             + (if $ENV.YUKTA_GH then {YUKTA_GH: $ENV.YUKTA_GH} else {} end)
             + (if $ENV.YUKTA_HEAL_EVENTS then {YUKTA_HEAL_EVENTS: $ENV.YUKTA_HEAL_EVENTS} else {} end)
             + (if $ENV.YUKTA_WARM_DELAY then {YUKTA_WARM_DELAY: $ENV.YUKTA_WARM_DELAY} else {} end)
             + (if $ENV.YUKTA_BUDGET_ISSUE then {YUKTA_BUDGET_ISSUE: $ENV.YUKTA_BUDGET_ISSUE} else {} end)
             + (if $ENV.YUKTA_BUDGET_EPIC then {YUKTA_BUDGET_EPIC: $ENV.YUKTA_BUDGET_EPIC} else {} end)
             + (if $ENV.YUKTA_NAV_BACKENDS then {YUKTA_NAV_BACKENDS: $ENV.YUKTA_NAV_BACKENDS} else {} end)
             + (if $ENV.YUKTA_NAV_IDE then {YUKTA_NAV_IDE: $ENV.YUKTA_NAV_IDE} else {} end)
             + (if $ENV.YUKTA_NAV_IDE_URL then {YUKTA_NAV_IDE_URL: $ENV.YUKTA_NAV_IDE_URL} else {} end)
             + (if $ENV.YUKTA_NAV_CBM_BIN then {YUKTA_NAV_CBM_BIN: $ENV.YUKTA_NAV_CBM_BIN} else {} end)
             + (if $ENV.YUKTA_NAV_CBM_CACHE then {YUKTA_NAV_CBM_CACHE: $ENV.YUKTA_NAV_CBM_CACHE} else {} end)
             + (if $ENV.YUKTA_NAV_INDEX_TIMEOUT then {YUKTA_NAV_INDEX_TIMEOUT: $ENV.YUKTA_NAV_INDEX_TIMEOUT} else {} end)
             + (if $ENV.YUKTA_PROJECT_STATUS_CMD then {YUKTA_PROJECT_STATUS_CMD: $ENV.YUKTA_PROJECT_STATUS_CMD} else {} end);
    def step($id; $action; $secs; $extra):
      {nodeId: $id, type: "PROCESS_EXECUTOR",
       config: {command: [$sh, $action], workingDir: "${start.repo}", timeout: $secs, timeoutSeconds: $secs,
                failureMode: "ERROR", env: (env + $extra)}};
    def pause($id; $signal; $secs; $onTimeout):
      {nodeId: $id, type: "WAIT_FOR_SIGNAL", config: {signal: $signal, timeoutSeconds: $secs, onTimeout: $onTimeout}};
    def edge($s; $t): {source: $s, target: $t};
    def chain($ids): [range(1; $ids | length) as $i | edge($ids[$i - 1]; $ids[$i])];
    def runner($wid; $inner; $desc):
      {workflowId: $wid, description: $desc,
       nodes: [{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
               {nodeId: "issue", type: "SUB_WORKFLOW",
                config: {workflowId: $inner, timeoutSeconds: 28800, routeByOutcome: true,
                         payload: {issue: "${start.issue}", repo: "${start.repo}", worktree: "${start.worktree}"}}},
               {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}},
               (step("release"; "release"; 600; {}) | .config.failureMode = "CONTINUE"),
               {nodeId: "escalate", type: "OUTCOME_TERMINAL",
                config: {outcome: "ESCALATE", reason: "the issue run did not finish: the issue is Ready again, the worktree is kept"}}],
       edges: [edge("trigger"; "issue"),
               {source: "issue", target: "done", sourcePort: "success"},
               {source: "issue", target: "release", sourcePort: "failure"},
               {source: "issue", target: "release", sourcePort: "escalate"},
               {source: "issue", target: "release", sourcePort: "retry"},
               edge("release"; "escalate")]};
    ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
      step("claim"; "claim"; 600; {}),
      step("worktree"; "worktree"; 1800; {}),
      step("brief"; "brief"; 600; {})]
     + (if $impl == "1" then [pause("before-implement"; "before-implement"; 3600; "CONTINUE")] else [] end)
     + [step("nav"; "nav"; 3600; {}),
        step("implement"; "implement"; 14400; {}),
        {nodeId: "check", type: "SUB_WORKFLOW",
         config: {workflowId: "check", timeoutSeconds: 14400,
                  payload: {project: "${start.worktree}", issueState: "${start.repo}/.yukta-dev/issue-${start.issue}"}}}]
     + (if $pr == "1" then [pause("before-pr"; "before-pr"; 14400; "FAIL")] else [] end)
     + [step("pr"; "pr"; 1800; {YUKTA_CHECK_EXECUTION: "${payload.executionId}"}),
        {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}}]) as $nodes
    | {workflows: {
        ("implement-issue" + $suffix): {workflowId: ("implement-issue" + $suffix),
          description: "Implement one issue in its own worktree with a code-navigation index, check and heal it, open a draft PR",
          nodes: $nodes, edges: chain($nodes | map(.nodeId))},
        ("run-issue" + $suffix): runner("run-issue" + $suffix; "implement-issue" + $suffix; "implement-issue, and the issue back to Ready when it does not end well")}}'
}

# epic_variant IMPL_PAUSE PR_PAUSE BETWEEN_PAUSE: the suffix of run-epic for the checkpoints that are off (0)
epic_variant() { echo "$(issue_variant "$1" "$2")$([[ "$3" == 1 ]] || echo "-no-between-pause")"; }

# epic_workflows ISSUESH IMPL_PAUSE PR_PAUSE BETWEEN_PAUSE: prints {"workflows": {"run-epic...": ...}}. The start
# payload is epic (the issue number) and repo. run-epic lists the epic's open, assigned, Ready sub-issues,
# splits them and runs run-issue for each, two at a time (a worktree each, so their checks do not collide):
#
#   list -> split -> [between-issues] -> issue (SUB_WORKFLOW, concurrency 2) --success--> ok -> done
#                                              \--failure/escalate/retry--> bad -> escalate (OUTCOME ESCALATE)
#                                                                        \-> bad-count (2 of them) -> pause
#
# Two issue runs that end ESCALATE pause the epic and the runs below it (epic-pause), for whoever steers to
# look: yk resume --tree to go on, yk stop --tree to give up. Every issue still ends with its own PR or Ready again.
epic_workflows() {
  local suffix
  suffix="$(epic_variant "$2" "$3" "$4")"
  jq -n --arg sh "$1" --arg issue_suffix "$(issue_variant "$2" "$3")" --arg between "$4" --arg suffix "$suffix" '
    def env: {YUKTA_EPIC: "${start.epic}", YUKTA_REPO_DIR: "${start.repo}"}
             + (if $ENV.YUKTA_GH then {YUKTA_GH: $ENV.YUKTA_GH} else {} end);
    def edge($s; $t): {source: $s, target: $t};
    def lane($s; $port; $t): {source: $s, target: $t, sourcePort: $port};
    def agg($id): {nodeId: $id, type: "AGGREGATE", config: {size: 1000, timeoutSeconds: 86400}};
    ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
      {nodeId: "list", type: "PROCESS_EXECUTOR",
       config: {command: [$sh, "epic-list"], workingDir: "${start.repo}", timeout: 600, timeoutSeconds: 600,
                failureMode: "ERROR", outputFormat: "JSON", env: env}},
      {nodeId: "split", type: "SPLITTER", config: {itemsPath: "payload[\u0027output\u0027][\u0027issues\u0027]"}}]
     + (if $between == "1"
        then [{nodeId: "between-issues", type: "WAIT_FOR_SIGNAL",
               config: {signal: "between-issues", timeoutSeconds: 3600, onTimeout: "CONTINUE"}}] else [] end)
     + [{nodeId: "issue", type: "SUB_WORKFLOW",
         config: {workflowId: ("run-issue" + $issue_suffix), timeoutSeconds: 28800, concurrency: 2, routeByOutcome: true,
                  payload: {issue: "${payload.issue}", repo: "${start.repo}", worktree: "${payload.worktree}"}}},
        agg("ok"), agg("bad"),
        {nodeId: "bad-count", type: "AGGREGATE", config: {size: 2, timeoutSeconds: 86400}},
        {nodeId: "pause", type: "PROCESS_EXECUTOR",
         config: {command: [$sh, "epic-pause"], workingDir: "${start.repo}", timeout: 600, timeoutSeconds: 600,
                  failureMode: "CONTINUE", env: (env + {YUKTA_BAD_COUNT: "${payload.count}"})}},
        {nodeId: "paused", type: "CONSOLE_TERMINAL", config: {}},
        {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}},
        {nodeId: "escalate", type: "OUTCOME_TERMINAL",
         config: {outcome: "ESCALATE", reason: "issues of the epic did not finish: each is Ready again, its worktree kept"}}]) as $nodes
    | ($nodes | map(select(.nodeId == "between-issues")) | length > 0) as $gated
    | {workflows: {("run-epic" + $suffix): {workflowId: ("run-epic" + $suffix),
        description: "Run the Ready, assigned sub-issues of an epic, two at a time",
        nodes: $nodes,
        edges: ([edge("trigger"; "list"), edge("list"; "split")]
                + (if $gated then [edge("split"; "between-issues"), edge("between-issues"; "issue")] else [edge("split"; "issue")] end)
                + [lane("issue"; "success"; "ok"), edge("ok"; "done")]
                + [("failure", "escalate", "retry") as $p | lane("issue"; $p; "bad")]
                + [("failure", "escalate", "retry") as $p | lane("issue"; $p; "bad-count")]
                + [edge("bad"; "escalate"), edge("bad-count"; "pause"), edge("pause"; "paused")])}}}'
}

# dev_session HEALSH ISSUESH: the shared session "yukta-dev", parameterised by the start payload so it serves
# every checkout and every issue worktree: check (a project given as ${start.project}) with the heal and fix
# workflows it hands failures to, and the issue workflows.
dev_session() {
  local healsh="$1" issuesh="$2" policy base ci v ip pp bp
  policy="$(cat "$here/heal.json")"
  base="$(render_session "$here/session.json" "/unused" | jq '.sessionId = "yukta-dev" | .name = "yukta-dev"')"
  # ponytail: the issue workflows call a `check` with the payload of the old generated check (project, issueState). yk run is
  # hidden until they are rebuilt on the static check (scripts/gen-check.py).
  ci="$base"
  for ip in 1 0; do
    for pp in 1 0; do
      v="$(issue_workflows "$issuesh" "$ip" "$pp")"
      ci="$(jq --argjson v "$v" '.workflows += $v.workflows' <<<"$ci")"
      for bp in 1 0; do
        v="$(epic_workflows "$issuesh" "$ip" "$pp" "$bp")"
        ci="$(jq --argjson v "$v" '.workflows += $v.workflows' <<<"$ci")"
      done
    done
  done
  echo "$ci"
}
