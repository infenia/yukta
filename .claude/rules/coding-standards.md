# Lombok & Coding Standards

- **Reduce Boilerplate**: Use Lombok annotations instead of manually writing getters, setters, equals, hashCode, or toString methods.
- **Preferred Annotations**:
  - **Modern Data Carriers**: Use `record` for immutable data classes instead of `@Value` or `@Data`.
  - **With-ers for Immutability**: Use `@With` on records or immutable classes to create new instances with one field changed (e.g., `user.withEmail("new@email.com")`).
  - **Logging**: Always use `@Slf4j` for loggers. Never manually instantiate `private static final Logger log`.
  - **Complex Object Creation**: Use `@Builder` on records or classes with >3 optional fields.
  - **Dependency Injection**: Use `@RequiredArgsConstructor` on Spring components to enable constructor injection for `final` fields.
  - **Utility Classes**: Use `@UtilityClass` for static-only helper classes to enforce finality and private constructors.
  - **Exception Handling**: Use `@SneakyThrows` for checked exceptions in lambdas or stream operations where appropriate.
  - **Decision Matrix**: Use Java Records for simple data containers; use Lombok for classes requiring inheritance or complex features like `@Builder` with `@Singular`.
  - **Validation**: When using `@Data`, ensure important fields for equality are explicitly handled if the default behavior is insufficient.

## Code Style & Quality Gates

### Formatting & License Headers
- **Spotless** enforces **Google Java Style Guide** (2-space indentation, 100-char line limit)
- Every Java file MUST have an Apache License 2.0 header (managed by Spotless)
- Always run `./gradlew spotlessApply` before committing

### Static Analysis
- **Checkstyle**: Style violations (config: `config/checkstyle/checkstyle.xml`)
- **PMD**: Code quality rules (config: `config/pmd/ruleset.xml`)
- **SpotBugs**: Bug detection (enabled via quality-conventions)
- **OpenGrep**: Security/best-practice scanning (config: `config/opengrep/.semgrep.yml`); runs as part of `check` only if the `opengrep` CLI is installed
- Checkstyle, PMD, and SpotBugs apply to both main and test source; only AOT-generated code is exempt
