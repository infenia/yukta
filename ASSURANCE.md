# Assurance Case

This document explains why Yukta can be trusted to meet its security requirements. It states
the security requirements, the threat model and trust boundaries, the secure design principles
the code follows, how common implementation weaknesses are countered, and the evidence for each
claim. It describes the alpha server (`0.1.x`); the settings behind each claim are specified
in [docs/operations/security.mdx](docs/operations/security.mdx).

## 1. Security Requirements

Yukta is a workflow server: a client defines a directed acyclic graph of plugin nodes, the
server runs it, and the server exposes the result through REST, a web UI, an MCP endpoint and a
CLI. A user who can run a workflow can ask the server to run commands, scripts and SQL, call
HTTP endpoints, touch files and call models. The security requirements follow from that.

| ID | Requirement |
| -- | ----------- |
| R1 | A default installation is reachable only from the machine it runs on. |
| R2 | Network access to the API requires authentication, and each caller gets only the access its role and sessions allow. |
| R3 | A workflow can run a process, reach a host or read an environment variable only when the operator has allowed it. |
| R4 | Secrets are not stored in workflow definitions and are not returned by read APIs. Log redaction is not guaranteed (see section 5). |
| R5 | A web page the user visits must not be able to drive a local server. |
| R6 | Every change to a running execution is attributable and recorded. |
| R7 | Input is bounded in size, time and shape so a request or script cannot exhaust the server by accident. |
| R8 | The software supply chain (dependencies, build, release) is monitored and verifiable. |

## 2. Threat Model

### Assets

* The host that runs the server, and everything it can reach: files, processes, databases, the
  network and its credentials.
* Workflow definitions, execution data and logs.
* API keys, worker tokens and `YUKTA_SECRET_*` values.
* The integrity of released artifacts.

### Actors

| Actor | Trust |
| ----- | ----- |
| `ADMIN` key holder | **Trusted.** May define workflows, so may run code on the server. |
| `OPERATOR` key holder | Trusted to start and steer runs, not to define workflows. |
| `VIEWER` key holder | Read-only. |
| Session-limited key holder | Trusted only for its own sessions. |
| Remote worker | Trusted with the shared worker token and mutual TLS, but limited to its own plugin types. |
| Unauthenticated network client | Untrusted. |
| Web page in the user's browser | Untrusted. |
| Content that flows through a workflow (messages, model output, HTTP responses) | Untrusted data. |
| Dependencies and CI actions | Third-party code, monitored. |

### Trust boundaries

1. **Network edge**, where requests enter through REST (`/api/**`), MCP (`/mcp`), management
   (`/actuator/**`) and the UI. Yukta serves plain HTTP, so TLS termination belongs to a reverse
   proxy in front of it. A native install binds to loopback by default. The container image binds
   `0.0.0.0` and so requires `YUKTA_API_KEY` (see
   [docs/operations/security.mdx](docs/operations/security.mdx)).
2. **Role boundary** between `VIEWER`, `OPERATOR` and `ADMIN`, and the session boundary for
   limited keys.
3. **Workflow authoring boundary.** Defining a workflow is a trusted operation by design. The
   server does not try to make an untrusted author safe.
4. **Plugin execution boundary** between the engine and the effects a node has on the host:
   processes, outbound HTTP and MCP calls, scripts, expressions, files, SQL and models.
5. **Control plane to worker boundary**, a persistent connection secured by token and mutual TLS.
6. **Build and release boundary**, from source to the signed, attested artifact.

### Principal threats and countermeasures

| Threat | Countermeasure | Req. |
| ------ | -------------- | ---- |
| Remote attacker reaches an unprotected server | Loopback-only default for a native install. The server refuses to start on a non-loopback address with no API key, which is why the container image requires one. | R1, R2 |
| Brute force or key theft | Long random keys, compared as SHA-256 digests in constant time. Keys can be stored hashed, so the server never holds the plain key. Several keys can be valid for rotation. | R2 |
| Privilege escalation between roles | Role checks on REST by method and path, and on every MCP tool call. Session-limited keys reach only their own sessions and never reach `/actuator/**`. | R2 |
| Path confusion bypassing the checks | Ambiguous paths (`;`, `//`, `..`, backslash, encoded slash, dot or percent) are refused with `400` while a key is set. | R2 |
| Malicious web page against a local server (CSRF, DNS rebinding) | With no key set, the `Host` header must be local or allow-listed, and a changing request with an `Origin` header must be same-origin or allow-listed. | R5 |
| Command execution through a workflow | `PROCESS_EXECUTOR` refuses every command until an allow-list is set. With an allow-list, shell mode is refused. | R3 |
| Server-side request forgery | `HTTP_REQUEST`, `MCP_TOOL_CALL` and `MCP_LIST_TOOLS` accept host allow-lists. The check uses the host the URL really addresses, so `https://good.com@evil.com/` counts as `evil.com`. | R3 |
| Environment and secret disclosure | `${env.*}` reads only an allow-list. Names containing `PASSWORD`, `SECRET`, `TOKEN`, `CREDENTIAL`, `PRIVATE` or `KEY` stay blocked even when listed. Secrets are `decrypted:name` references read from `YUKTA_SECRET_*`. Read APIs and the `get_workflow_details` MCP tool mask literal secrets, and a definition that still holds the mask is refused on save. | R3, R4 |
| Resource exhaustion by scripts and expressions | Scripts run with a time limit and a statement limit, with bounds on values and string size. Expressions have a time limit. Request bodies are capped at 1 MiB by default. | R7 |
| Prompt injection and unsafe model output | `LLM_CALL` and `MCP_TOOL_CALL` filters apply to every node: input and output deny patterns, input size limit, per-provider rate limits, an allow-list of MCP tools, and limits on argument size and content. A refused call fails as bad input and is not retried. | R3, R7 |
| Compromised control plane commanding a worker | A worker runs only the plugin types in its own configuration. The worker verifies the control plane's host name against its certificate. | R3 |
| Untraceable changes to a run | Control commands go through one ordered path and are recorded with the key name as actor when the caller used a named key (no actor is recorded when the server runs with no key or one unnamed key); `GET /api/control/audit` lists them, including refused ones. | R6 |
| Vulnerable or malicious dependency | Dependabot, Trivy, CodeQL, OpenGrep, SpotBugs and Scorecard run in CI. License compliance is checked. Known CVEs have been fixed by pinning dependency versions. | R8 |
| Tampered release | GitHub Actions pinned by digest, workflows linted with zizmor, build provenance attestation on release artifacts, and signing in `release/sign.sh`. | R8 |

## 3. Secure Design Principles

The design applies the principles of Saltzer and Schroeder, and each has a concrete form in
Yukta.

* **Secure by default.** Loopback only, no process execution, an allow-listed environment. An
  operator must opt in to every capability that widens exposure.
* **Fail-safe defaults and fail closed.** Anything not explicitly allowed is refused, and an
  unparseable body for a restricted key is refused with `403`, not skipped.
* **Least privilege.** Three roles and per-session keys. An `OPERATOR` cannot define a workflow.
  A worker runs only the plugin types it is configured for.
* **Complete mediation.** One security filter chain guards REST, MCP and management endpoints,
  and MCP additionally checks the role on every tool call. Every change to a run takes the same
  ordered command path.
* **Economy of mechanism and separation of concerns.** The data plane (messages through a
  graph) and the control plane (admit, steer, record) are separate. Plugins hold only business
  logic, while back-pressure, retries, timeouts and failure routing live once in the engine
  around every node. See [How Yukta works](docs/concepts/how-it-works.mdx).
* **Open design.** The security model, its limits and its non-goals are published in
  [docs/operations/security.mdx](docs/operations/security.mdx), including that workflow
  authorship is a trusted operation.
* **Defense in depth.** Authentication, roles, session scope, allow-lists, size and time limits
  and an audit trail each stand alone.
* **Psychological acceptability.** Safe defaults need no configuration. Exposing the server is a
  deliberate two-setting change, and the documentation explains each step.
* **Accountability.** Control commands are attributed and recorded.

## 4. Countering Common Weaknesses

| Weakness class | How it is countered |
| -------------- | ------------------- |
| Injection (OWASP A03, CWE-77, CWE-89, CWE-94) | Process execution is allow-listed and shell mode is refused with an allow-list. Scripts and expressions run in a restricted sandbox with time and statement limits, and scripts see plain copies of message data, not Java objects. SQL is executed by a node an `ADMIN` defined, which is part of the trusted authoring boundary. |
| Broken access control (A01) | Role and session checks as above. |
| Cryptographic failures (A02) | The server stores no passwords and generates no keys. API keys are stored as digests. Transport security is delegated to a TLS-terminating proxy, and worker links use mutual TLS. |
| SSRF (A10, CWE-918) | Host allow-lists, with the check made on the real host of the URL. |
| Cross-site request forgery and DNS rebinding | Host and Origin checks while no key is set. |
| Security misconfiguration (A05) | Safe defaults. The server refuses to start in an unsafe network configuration. |
| Vulnerable components (A06) | Dependency scanning and automated updates. |
| Software and data integrity (A08) | Pinned CI actions, attestation of release artifacts and signing. |
| Logging and monitoring failures (A09) | Audited control commands, ordered execution events and execution logs. Secrets are masked in read APIs, but execution logs are not redacted. |
| Memory-safety errors (CWE-119, CWE-416) | The server is Java and has no native code of its own. |
| Resource exhaustion (CWE-400) | Body, script, expression and argument limits. Back-pressure and bounded edge buffers in the engine. The scheduler admits, queues or refuses starts with `429`. |

## 5. Residual Risks and Non-Goals

The assurance case is honest about what Yukta does not claim.

* An `ADMIN` key can run code on the server. Give it only to people you would let run code
  there. Without any key, everyone who can reach the server has this right, which is why the
  default is loopback only.
* `HTTP_REQUEST` and `SQL_QUERY` connect to whatever address or database the workflow names;
  there is no allow-list for `SQL_QUERY`. `NOTIFY_TERMINAL` webhooks are not covered by the host
  allow-lists, and a listed host name that resolves to an unintended address is not blocked.
* Scripts have a time and statement limit but no heap limit, because GraalVM Community has none.
* Yukta serves plain HTTP. Keys travel in request headers, so clients not on the same host must
  reach it through a TLS proxy.
* `/actuator/health`, the API documentation and the web UI's static files need no key.
* Execution and error logs carry plugin messages and metadata unchanged, so a workflow that logs
  a secret leaks it to anyone who can read the logs. Log redaction is not implemented.
* Delivery is at-least-once, so a workflow with side effects must be idempotent.

## 6. Evidence

| Claim | Where to verify |
| ----- | --------------- |
| Defaults, roles, keys, allow-lists, limits | [docs/operations/security.mdx](docs/operations/security.mdx) |
| Architecture and separation of control and data planes | [docs/concepts/how-it-works.mdx](docs/concepts/how-it-works.mdx) |
| Static analysis and style | Checkstyle, PMD, SpotBugs and OpenGrep run in `./gradlew check`, configured under `config/` and `build-logic/` |
| Test coverage thresholds | JaCoCo gates in `build-logic`, and Codecov (see `codecov.yml`) |
| Security scanning | `.github/workflows/security.yml`, `codeql.yml`, `scorecard.yml` and `.github/SECURITY_SCANNING.md` |
| Dependency updates and license checks | `.github/dependabot.yml` and `.github/workflows/license-compliance.yml` |
| Release integrity | `.github/workflows/release.yml` (attestation) and `release/sign.sh` |
| Vulnerability handling | [SECURITY.md](SECURITY.md) |
| Review and decision process | [GOVERNANCE.md](GOVERNANCE.md) and [CONTRIBUTING.md](CONTRIBUTING.md) |

This document is reviewed when the security model changes, in the same pull request, and at
least once per release.
