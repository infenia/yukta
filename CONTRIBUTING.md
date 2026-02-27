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

## License

By contributing, you agree that your contributions will be licensed under the **Apache License, Version 2.0**.
