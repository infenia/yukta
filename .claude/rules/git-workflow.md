# Git Workflow & Commit Conventions

## Commit Conventions
Use **Conventional Commits**:
- `feat: description` - new features
- `fix: description` - bug fixes
- `docs: description` - documentation changes
- `refactor: description` - code refactoring (no behavior change)
- `test: description` - test additions
- `chore: description` - build/tooling updates
- `style: description` - formatting only (no code logic change)

Example: `feat: add reactive DAG workflow engine for plugin orchestration`

## Pull Request Workflow
1. Create branch from `main`
2. Run `./gradlew spotlessApply` to format code
3. Run `./gradlew check` to verify all quality gates pass
4. Submit PR with descriptive title and issue references
5. Ensure all tests pass before merge
