---
description: File a Yukta defect found while using the dev loop as a dogfood issue
argument-hint: <one-line title>
---
File "$ARGUMENTS" as a dogfood finding with `yk finding --title "$ARGUMENTS" --module <module> --kind bug --evidence '<the log, CONTROL, SLOW or RESULT line>' --repro '<command>'`.
Use `--dry-run` first when unsure; the same title again comments on the open issue instead of filing a duplicate.
