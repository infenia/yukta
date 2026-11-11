# Voice and terms

**Voice in three words:** clear · warm · confident. We sound like a senior colleague who has time for you, never a manual or a salesperson.

| Rule | Detail |
|---|---|
| Person | "you" for the reader; "we" only in tutorials ("let's save it") |
| Mood | present tense; imperative for steps ("Run the command") |
| Sentences | aim at 20 words or fewer, one idea each |
| Banned words | simply, just, obviously, easy, trivial, clearly, of course |
| First use | define a term in plain words once, then link to the glossary |
| Errors | say what happened, why, and what to do next |
| Humour | rare, never at the reader's expense |
| Reference pages | lean: no chatty intro, no opinions; empathy = predictability |
| Honesty | state limits and alpha status where they matter; never hide a catch |

## Page contract (Diátaxis)
- Exactly one quadrant per page: **Tutorial** (learn by doing, we), **Guide** (how-to, you), **Reference** (facts), **Concept** (explanation).
- Titles: tutorial "Your first X" / "Build X"; guide "Do Y" starting with a verb; reference "X reference"; concept "How X works" / "Why X".
- Every page except Reference opens with the reader-state block (`<ReaderState/>`). Every page ends with next steps (tutorial and guide) or related links.
- Tutorials show expected output after each step and a `Stuck?` note. Guides state prerequisites and a verify step. Link, don't embed: one sentence plus a link to the concept.
- Diagrams: one idea each, a caption that says what to notice, alt text.
- Screenshots only through `<UiShot name="…" caption="…"/>`. Never paste an image path.
- Front matter: `title`, `sidebarTitle`, `description` (one sentence outcome), `keywords`. No hand-typed version numbers except the pinned install command (`bump-docs.sh` owns it).

## Terminology (our term → use / avoid)
| Use | Means | Avoid |
|---|---|---|
| **workflow** | the flowchart: nodes + edges, saved in a session | pipeline, flow, DAG (as a name; fine in one sentence explaining "DAG") |
| **session** | a folder of workflows (and their versions) | project, namespace |
| **run** | one execution of a workflow (`execution` in the API) | job, instance |
| **node** | one step; roles trigger, processor, terminal | task, block |
| **edge / port** | the arrow / the named output a message leaves through | link, branch (unless routing) |
| **message** | what travels along edges | event, payload (except `payload` field) |
| **supervisor** | owner of a unit of work: watches, steps in, decides who may steer | control plane (retired), controller |
| **shell / engine** | what Yukta enforces / what decides (`rules`, `http`, `workflow`, `process`) | brain, policy |
| **input request** | a typed question a run asks (approval, choice, text…) | signal, approval ticket (the signal API is gone) |
| **deterministic step** | same input, same output: scripts, HTTP, SQL, rules, routing | non-AI |
| **AI step** | `LLM_CALL`, `MCP_TOOL_CALL`, decision plugins on a model | agentic node |
| **MCP server** | Yukta's `/mcp` endpoint an assistant connects to | "the MCP" |
| **MCP client nodes** | `MCP_LIST_TOOLS` / `MCP_TOOL_CALL` calling other servers | mixing with the above |
| **operator** | a person (or key with role OPERATOR) steering runs | admin (that is a role) |
