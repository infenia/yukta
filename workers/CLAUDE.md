# Workers Module CLAUDE.md

## Overview
Remote workers over RSocket (phase 1 of `core/docs/plans/10-remote-workers.md`): the control plane's
`WorkerController`/`WorkerSessionHandler` (route `worker.session`) and the worker's `WorkerAgent`, which
connects out, registers and renews its lease. The registry itself is `WorkerRegistry` in core.

## Build & Development Commands
```bash
./gradlew :workers:test          # includes a real control plane and worker in one JVM
./gradlew -Pyukta.workers=true :boot:bootRun
```

## Key Facts
- Control plane: `yukta.workers.token` + `spring.rsocket.server.port`. Worker: `yukta.workers.control-plane`,
  `yukta.workers.token`, `yukta.workers.id`.
- The first frame must be `REGISTER` with the token (constant-time compare); a refused worker gets `REJECT` and
  the stream ends. A closed stream removes only the registration it made.
- No node runs remotely yet; frames for that come with phase 2.
