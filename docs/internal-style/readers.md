# Reader cards

Every page names one of these readers and one Diátaxis quadrant. Quotes are the questions people actually ask.

## 1. Ravi, the evaluator (DevOps / backend developer)
- **Knows:** shell, REST, CI pipelines, maybe Airflow or Temporal. **Does not know:** Yukta words (session, port, supervisor).
- **Arrived because:** a script or CI pipeline "quietly stops being followed"; a colleague linked the repo.
- **Fears:** an afternoon lost on an alpha tool; a hidden catch.
- **Success:** a workflow runs, he sees each step's result and understands what it would take to run his own job.
- **Time:** 5 to 10 minutes. **Needs:** a fast win and honest limits (alpha, in-memory by default, one server unless HA).
- **Pages:** home, install, tutorial 1, "why flowcharts", compared-with-other-tools.

## 2. Maya, the AI-agent builder
- **Knows:** LLM APIs, Claude Code or another MCP client, prompts. **Does not know:** DAGs, ports, supervisor shell vs engine.
- **Arrived because:** her agent "reports done when it never ran the tests"; she wants a process the agent cannot skip.
- **Fears:** an agent with unlimited power; surprise token bills; losing control mid-run.
- **Success:** her assistant builds and starts a workflow over MCP; a step is a rule, another is an LLM call; a risky action waits for approval.
- **Pages:** tutorials 2, 3, 4, 5; AI guides; "AI-assisted configuration"; "who decides".

## 3. Omar, the platform / ops owner
- **Knows:** deploying services, API keys, Postgres, observability. **Does not know:** how runs are owned and steered.
- **Arrived because:** he has to run Yukta for a team and answer "who can stop or answer what?".
- **Fears:** an outage he cannot explain; people and agents steering the same run.
- **Success:** deployed, secured, observable; supervisors own units of work and operator access is deliberate.
- **Pages:** operate guides, supervise guides, configuration and security reference.

## 4. Lena, the plugin author / contributor
- **Knows:** Java or the plugin contract. **Does not know:** how plugins are discovered or tested.
- **Arrived because:** she needs a node Yukta does not have.
- **Success:** her plugin appears in the catalogue and runs in a workflow.
- **Pages:** "Write a plugin" guide, plugin reference. Internals (`docs/contributing`) live on GitHub only, never on the site.
