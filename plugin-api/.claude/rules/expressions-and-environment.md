---
paths:
  - "src/main/java/**/util/**"
---

# Reading the environment and expression safety

Moved from `core/CLAUDE.md`; loads when you touch the plugin-api utilities (`SpelUtils`, variables).

### Reading the environment from a workflow
`${env.X}` and `${sys.x}` read only an allowlist: `YUKTA_VAR_*` environment variables, `yukta.var.*`
system properties, a short list of harmless ones (`HOME`, `USER`, `PATH`, `user.name`, `os.name`,
...), and names listed in `yukta.variables.allowed` (system property) or `YUKTA_VARIABLES_ALLOWED`
(env), comma separated. Names that look like secrets stay blocked even if listed. Use `decrypted:`
for secrets. `GraalJsScriptEngine` lets scripts `Java.type` only `java.time.*` and a few value
classes and denies reflection on `Class`. `yukta.process.allowed-commands` (comma separated)
limits `PROCESS_EXECUTOR` to those executables and refuses `useShell`; with it empty, nothing runs unless
`yukta.process.allow-any=true`. Expressions (`SpelUtils`) may call only a short list of methods
(`SafeMethodResolver`); `${context.x}` reads plain values outside `yukta.*`; a script is cancelled
after `yukta.script.timeout-ms` (10 s) or `yukta.script.max-statements` (50 million). Request bodies above `spring.codec.max-in-memory-size`
(`YUKTA_LIMITS_MAX_BODY`, 1 MiB) get 413. Limit and priority changes pass the `ControlReviewer` and are
audited with the actor (`SetLimitsCommand`, `SetPriorityCommand`); the raw node command refuses signal
bodies (use the signal command).
