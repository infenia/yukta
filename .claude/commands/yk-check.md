---
description: Run the Yukta quality gate (self-healing) on the changed modules, or the ones you name
argument-hint: [module ...]
---
Run the check through Yukta and act on its RESULT line only.

1. Run `yk check` with a `-m <module>` for each module in "$ARGUMENTS" (none given: the modules with uncommitted
   changes, or the whole check when the change is outside every module). Use `run_in_background` for more than a module.
2. `status=SUCCESS`: say so in one line, listing `healed=` if any, and review those changes with `git diff` (the fixer never commits).
3. `status=FAILURE`: the stage in `failed_node=` is exhausted. Read only that stage's report, fix it, run the check again.
4. Report any defect of Yukta itself with `yk finding`. A heal that was slow or odd: `yk heal report`, `yk heal analyze`.
