## Codebase memory

Code navigation uses the `codebase-memory` MCP server (`codebase-memory-mcp`), not CodeGraph. Reach for its tools BEFORE grep/find or reading
files when you need to understand or locate code (symbols, callers and callees, architecture). If the project is not indexed yet
(`list_projects` is empty), run `index_repository` on the repo root first. If its tools are deferred, load them by name via tool search.

## Language servers (LSP plugins): evaluated 2026-10-31, not enabled

The official `jdtls-lsp`, `typescript-lsp` and `gopls-lsp` plugins are **not** in the committed `enabledPlugins`. Findings from trying them here
(jdtls with Java 25, `typescript-language-server`; `gopls` and the IntelliJ index were not available, and this repo has no `.codegraph/`
index, so CodeGraph could not be compared):

| | Result |
|---|---|
| `jdtls-lsp` (Java) | **Rejected.** On the 44-module Gradle build the server did not answer `goToImplementation` within 60 s (twice), and other calls got "server is starting". Worse, it does not run Lombok, so once it had loaded the project it reported dozens of false errors (`@UtilityClass` methods "static reference to non-static method", `@RequiredArgsConstructor` constructors "undefined", blank final fields "may not have been initialized", Spring AOT `__BeanDefinitions` classes). That noise lands in the context as new diagnostics. The plugin starts plain `jdtls` with no way to add the Lombok agent from the repo. |
| `typescript-lsp` (`ui/`) | Works, small answers: `goToDefinition` exact, `findReferences` for `useExecutionLogs` returned the 3 production hits (definition, import, call) in three short lines against 16 lines of `grep`; but the first call returned only the definition (project not loaded yet) and tests under the separate `tsconfig.vitest.json` are not covered. Useful for `ui/`, optional. |
| `gopls-lsp` (`cli/`) | Not tried: `gopls` is not installed. |

To try one yourself, install the binary and run `/plugin install typescript-lsp@claude-plugins-official` (user scope). To use the Java server
anyway, Lombok has to be on its command line (`-javaagent:<lombok.jar>`, for example through `JAVA_TOOL_OPTIONS`); untested here, and a
per-machine path, so it cannot be committed. For Java navigation use the IntelliJ index tools when the IDE is open (not tried here), or `grep`.

