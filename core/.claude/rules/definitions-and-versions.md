---
paths:
  - "src/**/service/workflow/**"
  - "src/**/service/session/**"
  - "src/**/model/session/**"
---

# Workflow definitions and their versions

Reference for the definition stores and versions. Moved from `core/CLAUDE.md`.

### Workflow definitions and their versions
With `yukta.session.store-type=file`, `FileWorkflowDefinitionStore` keeps definitions under
`<baseDir>/workflows/<session>/<workflow>/` (`current.json`, `history/<version>.json`; ids are
URL-encoded, writes are atomic and fsynced) so they survive a restart; `in-memory` keeps them in
memory. Both keep the newest 20 saved versions of a workflow (identical saves add none).
`WorkflowDefinitionStore.revisions/findRevision` and the web rollback endpoint use them. `GET
.../versions/diff?from=&to=` (`to` omitted: the current definition) returns a `WorkflowDiff` (nodes
added, removed or changed, edges added or removed); MCP has `list_workflow_versions`,
`diff_workflow_versions` and `rollback_workflow`, and the workflow page a "Versions" panel. A version can carry a note (`POST .../versions/note?version=&note=`, MCP `note_workflow_version`; `DefinitionRevision.note`; a column in PostgreSQL, a `.note` file next to the version file). A start whose payload has `yukta.version` runs that saved version (the prepared-workflow cache is bypassed). The workflow page's Versions panel shows and edits the notes.
