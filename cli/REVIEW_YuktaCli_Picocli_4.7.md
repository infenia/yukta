Task receipt

You're running the CLI with Spring Boot (`cli-boot` module). I will update the Picocli 4.7 review for `cli/src/main/java/com/infenia/yukta/cli/YuktaCli.java` to reflect the Spring-backed context: keep most picocli best-practices, adapt DI/bootstrapping guidance to use Spring, recommend build-driven version injection (BuildProperties), and call out where wiring already happens (CliRunner, CliConfiguration, YuktaCliApplication).

Plan / checklist

- Inspect the current `cli` and `cli-boot` modules (done).
- Keep picocli best-practices (annotations, arity, help text) unchanged.
- Adapt runtime and DI guidance to acknowledge Spring Boot wiring (`CliRunner`, `CliConfiguration`, `YuktaCliApplication`).
- Add concrete, prioritized recommendations that fit the Spring-backed setup (version injection via BuildProperties, test patterns, optional IFactory choice, ensure picocli-codegen for native builds).
- Replace the previous standalone/remove-Spring guidance with Spring-specific alternatives.

Summary (quick)

- The original review remains largely valid: Picocli usage guidance, testing strategies, security/logging, and picocli-4.7-specific notes still apply.
- Because you will run the CLI via Spring Boot (`cli-boot`), accept the Spring-backed wiring: `CliRunner` (ApplicationRunner) constructs and executes the picocli tree using Spring-managed beans; `CliConfiguration` provides WebClient and SystemExitHandler; `YuktaCliApplication` is the Spring Boot entrypoint.
- Update focus: prefer build-driven version injection (use Spring Boot BuildProperties), keep `SystemExitHandler` pattern, and confirm picocli codegen is enabled for AOT/native builds.

Checklist (quick pass)

- Confirm `@Command` metadata in `YuktaCli` is correct (name, description, version). With Spring Boot, prefer build-injected version.
- `mixinStandardHelpOptions = true` — good.
- `Runnable` vs `Callable<Integer>` — root may remain `Runnable`; commands that return exit codes should use `Callable<Integer>`.
- Bootstrapping: `CliRunner` registers subcommands and calls `CommandLine.execute(...)` — verify that behavior and test coverage.
- DI: Spring constructs command beans; explicit wiring via `CliRunner` is acceptable. Optionally implement a Spring-backed `IFactory` for lazy bean resolution.
- Confirm `DaemonProperties` binding via `@EnableConfigurationProperties(DaemonProperties.class)` in `CliConfiguration` is correct and defaults are applied.
- Keep `SystemExitHandler` pattern — good for testability.
- Ensure picocli-codegen annotation processor is configured and effective for native-image/AOT builds.
- Add tests covering help/version output and `CliRunner` behavior; use `SystemExitHandler` test double to avoid terminating test runs.

Expanded guidance (Spring-backed context)

1) Core picocli 4.7 features to use

What stays the same
- Continue using `@Command`, `@Option`, `@Parameters`, `parameterLabel`, `defaultValue`, `arity` and `@ArgGroup` where appropriate. These are orthogonal to Spring Boot.

Spring-specific note
- Keep `YuktaCli` minimal (it currently prints usage). Let `CliRunner` (Spring-managed) be the orchestrator that constructs the `CommandLine` tree and calls `execute(...)` with injected beans.

2) Runtime behavior (Runnable vs Callable, exit codes)

What stays the same
- Prefer `Callable<Integer>` when commands must return a distinct exit code. `Runnable` is acceptable for the root usage-only command.

Spring-specific note
- `CliRunner` invokes `rootCmd.execute(rawArgs)` and uses `SystemExitHandler` to centralize exit handling. This is a best-practice: command classes should not call `System.exit` directly. Keep this pattern.

3) DI and factories (Spring integration)

Original guidance
- The review recommended `IFactory` for DI integration. With Spring Boot in place, you have two valid patterns:
  - Explicit wiring (current): Spring constructs beans and `CliRunner` injects those instances and calls `addSubcommand(...)`. This is explicit and simple.
  - Spring-backed IFactory (optional): implement an `IFactory` that delegates to Spring's `ApplicationContext.getBean(...)` and pass it to `new CommandLine(spec, springFactory)`. This lets picocli lazily instantiate subcommands via Spring and supports scoped beans.

Actionable
- No immediate change required; current explicit wiring via `CliRunner` is fine. If you need lazy instantiation or to respect Spring scopes/lifecycle, implement a small `SpringFactory` implementing `picocli.CommandLine.IFactory`.

4) Testing strategies (with Spring)

What stays the same
- Unit tests should assert parsing, help text and behaviors by constructing command classes directly and using `CommandLine` with custom `PrintWriter`/`ByteArrayOutputStream`.

Spring-specific additions
- For integration-style tests that validate the real wiring and `CliRunner` behavior, use `@SpringBootTest` (or a sliced context) and inject a test `SystemExitHandler` bean that records exit codes without exiting the JVM.
- Example: define a test bean implementing `SystemExitHandler` that stores the last exit code and verify it after calling the Spring context with application args.

5) Packaging & distribution (Spring Boot specifics)

What stays the same
- Build a runnable artifact; `cli-boot` already configures `YuktaCliApplication` with `WebApplicationType.NONE` and Graal/native settings.

Spring-specific recommendations
- Version: use Spring Boot build info / BuildProperties to populate the CLI version. Configure the Gradle `bootBuildInfo` or `buildInfo` task to create `META-INF/build-info.properties`, then inject `org.springframework.boot.info.BuildProperties` into `CliRunner` or a startup component and set the version on the CommandSpec or use CommandLine.setVersionProvider at runtime.
  - Example (conceptual):
    - in Gradle: `bootBuildInfo()` or `springBoot { buildInfo() }` to generate build-info.
    - in `CliRunner` or a configuration bean: `rootCmd.getCommandSpec().version(buildProperties.getVersion());`
- Shell completion: use picocli's `CommandLine.generateCompletion` in a build step or a Spring boot task to produce completion scripts. `cli-boot` is the right place to run that generation for distributed builds.
- Native image: keep picocli-codegen enabled and ensure annotation processor output is available to `cli-boot` Graal build.

6) Security and logging (no change)

- Continue to use SLF4J (you already do with Lombok `@Slf4j`). Ensure no secrets are printed in logs or help.

7) Picocli 4.7-specific notes (confirm build)

- `cli/build.gradle.kts` includes `annotationProcessor(libs.picocli.codegen)` — verify the toolchain runs annotation processing (Gradle Java compile tasks) and that generated classes/resources are available to `cli-boot` during native image AOT.
- Confirm `libs.versions.toml` references a Picocli 4.7.x version.

8) File-specific review: `cli/src/main/java/com/infenia/yukta/cli/YuktaCli.java` (Spring-backed interpretation)

Context
- The project uses `YuktaCliApplication` (`cli-boot`) as the Spring Boot entrypoint. `CliConfiguration` (`@EnableConfigurationProperties(DaemonProperties.class)`) provides beans like `SystemExitHandler` and `WebClient`. `CliRunner` (an `ApplicationRunner`) wires command beans and executes the picocli tree.

Checks (do not modify file here; verification points)
- `@Command` metadata: `name = "yukta"` is fine as a root name; `mixinStandardHelpOptions = true` is present.
- `version` value: it is hard-coded to `"0.0.1"`. With Spring Boot, prefer generating build info and updating the command version at runtime from `BuildProperties` so `--version` prints the real build version.
- `Runnable` vs `Callable<Integer>`: Keeping `YuktaCli` as `Runnable` is acceptable since `CliRunner` handles execution/exit codes. Individual subcommands that return codes should implement `Callable<Integer>`.
- `run()` behavior: `CommandLine.usage(this, System.out)` simply prints usage; `CliRunner` is responsible for actual parsing and execution. This arrangement is appropriate for a minimal root command.
- Subcommands: `CliRunner` programmatically registers subcommands (see `CliRunner` source) — confirm all relevant command beans are present in the Spring context.
- DI and state: class is stateless — fine for root command.
- Logging and output: hard-coded `System.out` in `run()` is acceptable for a simple usage print; testability is preserved by other components using `SystemExitHandler` and by testing command classes directly.

Prioritized actionable recommendations (Spring-backed)

1) High priority — Version injection
- Replace the hard-coded `version` literal by populating the version from Spring Boot `BuildProperties` at startup. Implementation approaches:
  - Configure `bootBuildInfo()` in Gradle to generate `META-INF/build-info.properties`.
  - Inject `org.springframework.boot.info.BuildProperties` into `CliRunner` or a `@Configuration` bean and set the CommandSpec version programmatically before calling `execute(...)`:
    - `rootCmd.getCommandSpec().version(buildProperties.getVersion());`
  - Alternatively, implement a small VersionProvider that reads `BuildProperties` and register it with `@Command(versionProvider = YourVersionProvider.class)`.

2) High priority — Confirm picocli-codegen and native-image integration
- Verify annotation processing runs in Gradle builds and the generated metadata is available for `cli-boot` Graal native-image builds.

3) Medium priority — Test `CliRunner` wiring
- Add an integration test using `@SpringBootTest` that injects a test `SystemExitHandler` bean to capture exit codes and asserts expected behavior when calling the Spring Boot app with `--help`, `control --help`, etc.

4) Medium priority — Consider `IFactory` only if needed
- Current explicit wiring is acceptable. If you want bean lifecycle control, lazy instantiation or scope support, implement a small `SpringFactory implements IFactory` that delegates to `ApplicationContext.getBean(...)` and pass it into `new CommandLine(factory)`.

5) Low priority — Output redirection for tests
- Use `rootCmd.setOut(...)` / `setErr(...)` in `CliRunner` during tests or have tests instantiate a `CommandLine` with custom writers to capture and assert help text.

Examples (conceptual) — version via BuildProperties

- Gradle: enable build-info generation (in `cli-boot` or root build script)
  ```kotlin
  // build.gradle.kts
  springBoot {
    buildInfo()
  }
  ```

- In a Spring bean (e.g., in `CliRunner` before `rootCmd.execute(...)`):
  ```java
  @Autowired
  private BuildProperties buildProperties; // optional, may be null in dev builds

  // before execute
  if (buildProperties != null) {
    rootCmd.getCommandSpec().version(buildProperties.getVersion());
  }
  ```

Note: you can also provide build timestamp, group, artifact, etc., from BuildProperties for richer `--version` output.

Concluding notes

- `REVIEW_YuktaCli_Picocli_4.7.md` has been updated to reflect that you will run the CLI with Spring Boot. The majority of picocli best-practices still apply unchanged; the main shifts are around DI/bootstrapping (Spring-managed beans via `CliRunner`), version injection (use BuildProperties), testing (mix of direct unit tests and Spring integration tests), and native/AOT handling (ensure picocli-codegen output is available to `cli-boot`).

Would you like me to:
- (A) implement BuildProperties-based version wiring in `CliRunner` (small code + build change), or
- (B) add an integration test demonstrating `SystemExitHandler` mocking and verifying `CliRunner` behavior, or
- (C) just leave the review file updated (done) and continue with other checklist items on request?