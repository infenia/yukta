# Workers Module CLAUDE.md

## Overview
Remote workers over RSocket (ADR `design/decisions/0006-remote-workers.md`): the control plane's
`WorkerController`/`WorkerSessionHandler` (route `worker.session`), `WorkerNodeExecutor` (runs a node attempt on a
worker) and `WorkerConnections`; the worker's `WorkerAgent` (connects out, registers, renews its lease) and
`NodeRunner` (route `node.run`, runs the attempts). The registry is `WorkerRegistry` in core.

## Build & Development Commands
```bash
yk gradle :plus:workers:test          # includes a real control plane and worker in one JVM
./gradlew -Pyukta.workers=true :boot:bootRun
```

## Key Facts
- Control plane: `yukta.workers.token` + `spring.rsocket.server.port`. Worker: `yukta.workers.control-plane`,
  `yukta.workers.token`, `yukta.workers.id`.
- The first frame must be `REGISTER` with the token (constant-time compare); a refused worker gets `REJECT` and
  the stream ends. A closed stream removes only the registration it made.
- A node attempt is one `requestChannel` on the worker's own connection; the worker runs only the plugin types in
  `yukta.workers.plugin-types`. The tests start a real control plane and worker in one JVM.
- `WorkerNodeExecutor` picks the worker and takes its slot in the same step (compare-and-set on its in-flight count), so concurrent picks never overbook one; a missing worker is a failure the engine can retry. `NodeRunner` refuses an attempt beyond `yukta.workers.capacity` with a retryable `IllegalStateException`.
- Live logs: `NodeRunner` puts a `PluginLogSink` into the plugin's context that sends `LOG` frames (`{stream, line, level}` in the frame's `message`); `WorkerNodeExecutor` hands them to the node's own sink. `LOG` raised the wire protocol to 2 (`WorkerFrame.PROTOCOL`), so a worker and its control plane must be the same release.
- Tracing: `WorkerNodeExecutor` opens a `yukta.worker.call` sender observation (parent: the node's observation from `TaskTrackerService.observation`, else the run's) whose propagating handler writes the trace headers into START's `message`; `NodeRunner` opens a `yukta.worker.attempt` receiver observation from them and puts it in the plugin's context (`ObservationThreadLocalAccessor.KEY`). Without a tracer the headers are empty. Plugin meters stay on the worker.
- Meters (control plane): `yukta.worker.bytes{direction}` counts the payload bytes of every channel on the worker connections (`WireMeter`, an RSocket interceptor on the server: after the codec, before fragmentation and TLS); `yukta.worker.attempts{module,outcome}` (`completed`, `failed`, `lost`, `cancelled`, `no_worker`) and `yukta.worker.dispatch{module}` (pick to the worker's first answer) are timers in `WorkerNodeExecutor`. The data MIME type is `application/cbor` (Spring Boot's RSocket strategies; `RemoteNodeTest` states it). `perf/run.sh` with `WORKERS=n` measures them (see `perf/README.md`).
- Draining: `WorkerAgent` is a `SmartLifecycle`; its `stop(callback)` sends a lease with `draining=true`, polls `NodeRunner.running()` reactively until it reaches zero (at most `yukta.workers.drain-timeout`), then closes the connection and runs the callback. No thread is parked; Spring waits for the callback up to `spring.lifecycle.timeout-per-shutdown-phase` (30s by default), so raise that with the drain timeout.
- TLS/mTLS: `WorkerTransport` connects with TCP or, with `yukta.workers.ssl-bundle`, TLS using the bundle's key and trust stores (host name verified). The test makes certificates with keytool and checks that a worker with a trusted certificate registers and one with an untrusted certificate or none does not.
- `ChaosTest` starts workers as separate JVMs (`WorkerMain`) and kills one with SIGKILL during an attempt: the attempt fails retryably and the retry runs on the other worker.
- `yukta.workers.control-plane` takes several addresses; a standby answers REJECT ("not the leader") and the agent's next attempt tries the next address.
- The package is `com.infenia.workers`, outside the application's component-scan root (`com.infenia.yukta`): `WorkerController` and `NodeRunner` are `@Controller`s and would otherwise be scanned without their properties and stop the application from starting. `boot` has acceptance tests for the module (run with `-Pyukta.workers=true`).
- Native image: Spring decides property-conditioned beans when the image is built. Build the image as the role it will have: `-Pyukta.workers=true -Pyukta.workers.native=control-plane|worker` (see `boot/build.gradle.kts`); the real token, address and id are read when it starts. A native image built without that switch warns at build time and refuses to start when given worker or control-plane settings (`NativeRole`, checked by the unconditional `workersNativeRoleCheck` bean). `native-plus-smoke.yml` builds both roles weekly and runs `release/workers-smoke.sh` (registration plus a placed MAPPER node; it also runs against two JVM jars with `WORKERS_SMOKE_PORT`), which checks the frame records' reflection hints (`@RegisterReflectionForBinding` for `WorkerFrame`, `NodeFrame`, `DefaultMessage`).
