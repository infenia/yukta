---
paths:
  - "processors/internal/**"
---

# Sub-workflows

Moved from `core/CLAUDE.md`; loads when you touch the internal-core plugins (`SUB_WORKFLOW`, `WAIT_FOR_SIGNAL`).

### Sub-workflows
`SUB_WORKFLOW` (internal-core) runs another stored workflow of the same session per message through
`SubWorkflowGateway` (`SubWorkflowRunner`): a normal run with its own execution id, tracked
like any other, whose terminal nodes feed a `ResultCollector` in the Reactor context. The
node emits `{executionId, results[]}`; a failing child fails the node. The child start payload
carries `yukta.depth` and `yukta.parentExecutionId`; nesting is limited to 5. With
`yukta.execution.max-concurrent` a child runs on the slot its parent holds (`ExecutionScheduler.borrowed()`),
so a parent that waits for its children cannot starve them; children are not counted in the limits or
the running gauge and stay bounded by the parent node and the depth. The child is subscribed inside the
parent node, so cancelling the node cancels the child, which ends `CANCELLED`; the link to the parent
is the `yukta.parentExecutionId` in the child's start payload (and its `RUN_STARTED` log entry).
