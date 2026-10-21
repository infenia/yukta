# CLI: assessment

How the `cli` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/cli.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Acli), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Acli) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Acli) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

A practical order: fix what misleads users first, then close the gaps to the server, then make the link to the server self-checking.

1. **Today.** Five command groups (about forty commands, including watch, result, node retry, breakpoint and signal), 100% function coverage, pinned tools, releases with checksums. The first page of every list, no retries, no resume.
2. **Make it honest.** Done: `session apply` is registered. Next: refresh the README, fix the root description, silence the duplicate error output, add `yukta-cli version`, and test that every written command is registered. Risks: `docsStale`, `errorNoise`, `noVersion`.
3. **Close the main gaps.** Paging flags, stop mode, idempotency key, empty reason fix, and the commands still missing (validate, versions, approvals, scheduler limits and priority, recover, supervisors). Done: events (`controlbus watch`), node retry, breakpoint, signal, result. Risks: `noPaging`, `stopMode`, `idemKey`, `emptyReason`, `missingCmds`.
4. **Robust in pipelines.** Opt-in retries, stream reconnect with Last-Event-ID, distinct exit codes, consistent -o, token safety warnings. Risks: `noRetry`, `streamLoose`, `outputUneven`, `tokenPlain`.
5. **Self-checking against the server.** A contract test (real server or OpenAPI) in CI, a registration test over the command tree, and a move off deprecated paths. Risks: `dtoDrift`, `legacyPaths`, `testsTwice`, `winBuild`, `fileOrJson`, `localTimestamps`, `keywordProblems`.

## Quality lenses

### Scalability

A command-line client barely has a scaling story: one short-lived process, one request. What scales badly is not the program but its hand-written copy of the API (every new endpoint needs hand edits) and its inability to see past the first page of a long list.

**Ceilings today**

- Lists stop at the server's first page (500 by default, 1,000 at most) with no way to continue
- One hand-written function and command per endpoint: the work grows with the API
- A single stream event larger than 1 MB ends the stream
- Many parallel CLI calls from CI each open their own connection; no shared pooling

**Levers today**

- Streams do not buffer: events are printed as they arrive
- Static binaries scale to any number of build agents without installs

**Needs design work**

- --limit and --cursor flags or an automatic follow of X-Next-Cursor
- A generated client or a contract test to stop manual drift
- A larger or configurable line limit

### Performance

Start-up is fast because there is no runtime to boot: it is a static Go binary. The real latency is the network round trip and the server. Performance is not a concern of this module.

**Ceilings today**

- Each command pays one connection setup (and TLS handshake if https)
- Whole answers are read into memory before printing
- 30-second default limit may be too short for large answers

**Levers today**

- Milliseconds of start-up; no JVM
- Request body and flags are tiny
- No retries, so no hidden extra load on the server

**Needs design work**

- Nothing urgent. A batch mode could save reconnects if scripts call it hundreds of times

### Availability

The CLI is only as available as the server. It fails fast (30 s) and never retries, which is predictable but unforgiving for flaky networks and for the 'at limit' answer the server gives on purpose.

**Ceilings today**

- No retries or back-off on 429, 5xx or network errors
- Streams do not reconnect, and a clean close looks like success
- Exit code 1 for every failure, so scripts cannot react differently to each
- A hung stream with a silent server waits forever

**Levers today**

- A time limit on every normal call
- Ctrl-C always works on streams
- Clear 'request failed' versus 'API error' wording

**Needs design work**

- Opt-in retry for safe GETs and for starts with an idempotency key
- Reconnect with Last-Event-ID
- Distinct exit codes for usage error, server error and timeout

### Security

The CLI is a credential carrier. It sends a bearer token to whatever address it is given, and the server's API key is the only protection on an open server. The server's default is loopback only, and the CLI's default URL matches that.

**Ceilings today**

- Token sent over plain http with no warning
- `--token` on the command line is visible in shell history and the process list
- No control over TLS (custom CA, client certificates)
- Path escaping protects ids, but nothing validates the server's identity beyond normal TLS

**Levers today**

- Ids are escaped, so a hostile id cannot redirect a request
- Token read from the environment by default
- gosec and govulncheck run in the build; releases carry checksums and provenance
- User-named files are read deliberately and annotated for the scanner

**Needs design work**

- A warning (or refusal) when a token would go over http to a non-loopback host
- Documented advice to use YUKTA_TOKEN rather than --token
- A way to trust a private CA

### Operability

Easy to build and test, harder to support in the field. A user cannot tell which version they run, errors appear twice with help text, and the documentation describes commands and files that do not exist.

**Ceilings today**

- No version command and no embedded version
- Errors printed twice and wrapped in usage text
- README out of date: wrong binary names, a `goBuildDev` task that does not exist, a config file and `--file` flag that do not exist, `/api/v1` paths
- Windows developers need make and a POSIX shell

**Levers today**

- Release workflow with SHA256SUMS and provenance
- One source of truth for the build (the Makefile)
- Clear error prefixes: request failed versus API error

**Needs design work**

- A `version` command with ldflags
- Silenced duplicate error output
- Refresh the README and CLAUDE.md, add a test that every command listed in help is registered

### Evolvability

Adding a command is easy and cheap because of the shared builder, and the code is small and fully covered. The weakness is the link to the server: nothing breaks when the server changes, so the CLI quietly drifts.

**Ceilings today**

- Hand-written DTOs and paths with no contract test
- Server marks the paths the CLI uses as deprecated
- Registration by hand: a finished command can stay unreachable
- Shared package-level output variable

**Levers today**

- Generic Action helper keeps new simple commands to a few lines
- Tests use a real HTTP server, so refactors are safe
- 100% per-function coverage

**Needs design work**

- A contract test against the server's OpenAPI document or a real server in CI
- A test that walks the command tree and compares it with a list of supported endpoints
- Move to the non-deprecated paths with care for the session check

## Questions to settle

- **Scope.** Is the CLI a full admin tool or a small CI helper? The answer decides whether the 12 missing endpoints matter. (risk `missingCmds`)
- **Correctness.** With more than 500 sessions, what should `session list` do? Truncate silently, warn, page, or fail? (risk `noPaging`)
- **Security.** Would you let a token go over plain http to a non-loopback address? What is the right default? (risk `tokenPlain`)
- **Reliability.** A pipeline starts a run and the call times out. What should it do next, and what does the CLI offer? (risk `idemKey`)
- **Availability.** The stream ends with no error and no final status. Is that success, failure or unknown? (risk `streamLoose`)
- **Evolvability.** How would you notice, today, that the server renamed a response field? Who finds out first? (risk `dtoDrift`)
- **Quality.** session apply was written, tested and promised by the README but not registered, and is registered now. What test would have caught it, and does one exist for the other commands? (risk `docsStale`)
- **Operability.** What exit codes would your automation want, and who should own that contract? (risk `errorNoise`)
- **Governance.** Where should the CLI documentation come from so it cannot go stale: Cobra itself, or the Makefile? (risk `docsStale`)
- **Migration.** The server plans to drop the session-qualified paths. What do you lose, and how would you keep the cross-session safety check? (risk `legacyPaths`)

## Last risk review

Review of 2026-10-15 against main (after PRs #371, #373 and #374): 1 risk closed (applyMissing: session apply is registered), 4 updated (missingCmds is partly closed; legacyPaths, streamLoose and docsStale have new facts), 2 added (localTimestamps, keywordProblems); the other 13 are unchanged because their code is unchanged.
