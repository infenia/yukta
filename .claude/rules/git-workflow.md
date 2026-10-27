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
2. Run `yk gradle spotlessApply` to format code
3. Run the tests of what you changed with `yk gradle` (paste its RESULT line into the PR)
4. Submit PR with descriptive title and issue references
5. Ensure all tests pass before merge
