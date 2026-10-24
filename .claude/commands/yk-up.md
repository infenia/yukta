---
description: Start or refresh the Yukta dev server (built from origin/main, port 9858) and show its state
---
Run `yk up` (it builds origin/main on a new commit, which takes minutes, and swaps the server only while nothing runs),
then `yk status`. Report the one status line; if it says `STALE`, say a swap is pending because runs are active.
