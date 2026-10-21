# Contributing to Yukta

First off, thank you for considering contributing to Yukta! It's people like you that make Yukta such a great tool.

Yukta is an open-source project developed under **Infenia Private Limited**. We welcome contributions from everyone.

## Code of Conduct

By participating in this project, you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).

## How Can I Contribute?

### Reporting Bugs

* Check the [issue tracker](https://github.com/infenia/yukta/issues) to see if the bug has already been reported.
* If not, open a new issue. Include:
    * A clear and descriptive title.
    * Steps to reproduce the bug.
    * Expected behavior vs. actual behavior.
    * Screenshots or logs if applicable.
    * Your environment (OS, Java version, Gradle version).

### Suggesting Enhancements

* Check the issue tracker to see if the enhancement has already been suggested.
* Open a new issue with the "enhancement" label.
* Describe the feature in detail and why it would be useful.

### Pull Requests

1. **Fork the repository** and create your branch from `main`.
2. **Setup your development environment** (see [Development Setup](docs/development-setup.md)).
3. **Make your changes**. Ensure your code follows the project's coding standards.
4. **Run quality checks**:
    * `./gradlew spotlessApply` to format your code.
    * `./gradlew check` to run tests and static analysis (Checkstyle, PMD, SpotBugs).
5. **Write tests** for any new functionality.
6. **Update documentation** if you're adding or changing features.
7. **Submit a Pull Request**.
    * Use a descriptive title.
    * Reference any related issues.
    * Provide a summary of your changes.

### What CI runs on your PR

CI builds the modules your PR changes **plus every module that depends on them**, with the
full set of gates (tests, Checkstyle, PMD, SpotBugs, OpenGrep, JaCoCo). A change to the build
itself (`build-logic/`, `gradle/`, `config/`, `.github/workflows/`, ...) builds everything, and a
docs-only PR builds nothing. The `Detect Changed Modules` job summary shows the plan.

* Want the whole build anyway (e.g. an interface change used through reflection)? Add the
  `ci:full` label and re-run CI.
* `CI Gate` is the one required check.
* `main` and a nightly run build everything; a failing nightly opens a `ci-nightly` issue.

Details: [.github/WORKFLOWS.md](.github/WORKFLOWS.md).

## Coding Standards

* We follow the **Google Java Style Guide**.
* We use **Java 25** (with Java 21 toolchain compatibility).
* **PMD** and **Checkstyle** are enforced during the build.
* All new methods should have Javadoc.
* Keep methods small and focused (Single Responsibility Principle).

## Commit Messages

We prefer **Conventional Commits**:

* `feat: ...` for new features.
* `fix: ...` for bug fixes.
* `docs: ...` for documentation changes.
* `style: ...` for formatting, missing semi colons, etc; no code change.
* `refactor: ...` for refactoring production code.
* `test: ...` for adding missing tests.
* `chore: ...` for updating build tasks, etc; no production code change.

## License and Contributor License Agreement

Contributions are licensed under the licence of the directory they change: **Apache License 2.0**
everywhere, **AGPL-3.0-only** inside `plus/`. See [`LICENSING.md`](LICENSING.md).

Before your first pull request can be merged you must sign the Contributor License Agreement
([individual](CLA.md) or, for an employer, [corporate](CLA-corporate.md)). The CLA bot comments on
your pull request with the one line to reply. The agreement lets Infenia also offer the Work under
commercial licences. Infenia employees and bots are exempt.
