# Licensing

Yukta comes in three editions. Only the first two are in this repository.

| Edition | Licence | Where | For whom |
|---|---|---|---|
| **Community** | [Apache-2.0](LICENSES/Apache-2.0.txt) | Everything outside `plus/` | Anyone: try it, embed it, run it on a single server |
| **Plus** | [AGPL-3.0-only](LICENSES/AGPL-3.0-only.txt), or a commercial licence | `plus/` | Single-node durable storage on disk, for teams happy with the AGPL |
| **Enterprise** | Proprietary | A separate private repository | High availability, PostgreSQL, remote workers, enterprise security |

## What this means in practice

- **Using Community** needs nothing beyond the Apache-2.0 terms. This is what you get from the
  default build, the Docker image and the installers.
- **Plus** adds storing sessions, workflows, the execution log, checkpoints and chat history on
  disk. Build it with `-Pyukta.edition=plus`. Without Plus, those settings
  (`yukta.session.store-type=file`, `yukta.execution-log.dir`, `yukta.restart.dir`,
  `yukta.session-memory.dir`) stop start-up with a clear message; state otherwise stays in memory.
- **The AGPL applies to the Plus build as a whole**, because it combines Apache and AGPL code.
  Running it unmodified inside your company costs you nothing. If you modify it and let other
  people use it over a network, section 13 of the AGPL requires you to offer them your modified
  source. A Plus server reports where the source is under `edition` in `/actuator/info`.
- **Community never depends on Plus.** CI fails if any module other than `boot` (behind the
  `yukta.edition` switch) depends on `:plus`.
- **A commercial licence** for Plus is available to anyone who cannot accept the AGPL. Contact
  Infenia Private Limited (https://infenia.com).

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
