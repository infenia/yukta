---
description: Pick up assigned, Ready issues and run each through Yukta (worktree, implementation, self-healing check, draft PR)
argument-hint: [count | issue number]
---
Use the pickup skill for "$ARGUMENTS": list the issues assigned to me that are Ready, then run each with
`yk run issue <n> --detach` and steer it (checkpoints `before-implement` and `before-pr`) as the skill says.
