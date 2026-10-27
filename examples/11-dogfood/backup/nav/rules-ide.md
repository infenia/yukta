
Code navigation (the IDE's index):
- The task names a project path. Search with the `ide_*` tools of the intellij-index server (find_references, find_definition, find_implementations, find_class, find_file, search_text, call_hierarchy, type_hierarchy) instead of reading files one by one, and always pass that project_path.
- These tools only read. Edit files with your normal tools; call ide_sync_files before you search again after an edit.
- Use `codegraph` and plain grep only when an index tool cannot answer.
