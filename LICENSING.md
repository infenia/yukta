# Licensing

Yukta comes in three editions. Only the first two are in this repository.

| Edition | Licence | Where | For whom |
|---|---|---|---|
| **Community** | [Apache-2.0](LICENSES/Apache-2.0.txt) | Everything outside `plus/` | Anyone: try it, embed it, run it on a single server |
| **Plus** | [AGPL-3.0-only](LICENSES/AGPL-3.0-only.txt), or a commercial licence | `plus/` | Durable state (disk, PostgreSQL), high availability and remote workers, for teams happy with the AGPL |
| **Enterprise** | Proprietary | A separate private repository (not created yet) | Enterprise security, multi-tenancy and the other features built there; PostgreSQL, HA and workers will move there later |

## What this means in practice

- **Using Community** needs nothing beyond the Apache-2.0 terms. This is what you get from the
  default build, the Docker image and the installers.
- **Plus** adds storing sessions, workflows, the execution log, checkpoints and chat history on
  disk (`-Pyukta.edition=plus`), PostgreSQL storage with leader election for high availability
  (`-Pyukta.persistence=postgres`, `plus/persistence-postgres`), and remote workers
  (`-Pyukta.workers=true`, `plus/workers`). For now these two modules are in Plus; they are planned
  to move to Enterprise when its repository exists, and code already published stays available
  under the licence it was published with. Without Plus, those settings
  (`yukta.session.store-type=file`, `yukta.execution-log.dir`, `yukta.restart.dir`,
  `yukta.session-memory.dir`) stop start-up with a clear message; state otherwise stays in memory.
- **The AGPL applies to the Plus build as a whole**, because it combines Apache and AGPL code.
  Running it unmodified inside your company costs you nothing. If you modify it and let other
  people use it over a network, section 13 of the AGPL requires you to offer them your modified
  source. A Plus server reports where the source is under `edition` in `/actuator/info`.
- **Named API keys, roles and session-limited keys** are planned for Enterprise but are still in the public build today, behind the `ApiAuthenticator` hook; they move when the Enterprise repository exists.
- **Community never depends on Plus.** CI fails if any module other than `boot` (behind the
  `yukta.edition` switch) depends on `:plus`.
- **A commercial licence** for Plus is available to anyone who cannot accept the AGPL. Contact
  Infenia Private Limited (https://infenia.com).

## Extending Yukta

An extension is a module that replaces one of the single-server defaults or adds a feature to a Plus build.

- **The hooks are Apache-2.0 and live in `core`**: the stores (`SessionConfigStore`, `WorkflowDefinitionStore`, `ExecutionLogStore`, `NodeCheckpointStore`, `KeyValueStore`, `SessionHistoryStore`), `LeaderElection`, `RemoteNodeExecutor`, `ApiAuthenticator` and `AuditSink`. A module provides a bean of the same type and the default steps aside. The community build must never depend on `plus`, so these cannot move there.
- **The integration layer is AGPL-3.0-only and lives in `plus`**: the `EnterpriseModule` extension API and its registry (version check, features, licence verification hook), the `EditionInfo` of a Plus or Enterprise build, and the contract tests (`testFixtures` of `plus`) that a store or election must pass. A module built on this layer is part of a Plus build and follows the AGPL; ask Infenia about a commercial licence if that does not suit you.
- Infenia's own Enterprise modules are built under Infenia's copyright and are not bound by the AGPL for that reason.
- How far the AGPL reaches into a module that only implements the Apache hooks is a legal question that this file does not settle, and it is not legal advice.

## Files

- Every source file carries an SPDX header; `plus/**` files say `AGPL-3.0-only`. The repository
  follows the [REUSE](https://reuse.software/) specification (`REUSE.toml`, `LICENSES/`).
- The Apache-2.0 code that was published before `plus/` existed stays Apache-2.0 for anyone who
  already has it; the AGPL covers what is in `plus/` from now on.

## Contributing

Contributions are licensed under the licence of the directory they change, and need a signed
Contributor License Agreement ([individual](CLA.md), [corporate](CLA-corporate.md)); see
[CONTRIBUTING.md](CONTRIBUTING.md). The CLA texts are drafts pending legal review.

This file explains the structure and is not legal advice.
