# Workers Module CLAUDE.md

## Overview
Remote workers over RSocket (phases 1 and 2 of `core/docs/plans/10-remote-workers.md`): the control plane's
`WorkerController`/`WorkerSessionHandler` (route `worker.session`), `WorkerNodeExecutor` (runs a node attempt on a
worker) and `WorkerConnections`; the worker's `WorkerAgent` (connects out, registers, renews its lease) and
`NodeRunner` (route `node.run`, runs the attempts). The registry is `WorkerRegistry` in core.

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
- A node attempt is one `requestChannel` on the worker's own connection; the worker runs only the plugin types in
  `yukta.workers.plugin-types`. The tests start a real control plane and worker in one JVM.
- `WorkerNodeExecutor` picks the worker; a missing worker is a failure the engine can retry.
- Draining: `WorkerAgent.stop()` sends a lease with `draining=true`, waits for `NodeRunner.running()` to reach zero (at most `yukta.workers.drain-timeout`), then closes the connection.
- TLS/mTLS: `WorkerTransport` connects with TCP or, with `yukta.workers.ssl-bundle`, TLS using the bundle's key and trust stores (host name verified). The test makes certificates with keytool and checks that a worker with a trusted certificate registers and one with an untrusted certificate or none does not.
