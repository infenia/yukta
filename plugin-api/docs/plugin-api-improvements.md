# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# plugin-api — Remaining improvements

> Review of 2026-10-09; A1–A12 and A14–A18 were fixed on 2026-10-10.
> Companion: [`plugin-api-module-guide.html`](plugin-api-module-guide.html).

### A13 · P3 · `Plugin` mixes runtime, validation, UI and optimiser concerns — Effort M
- **Where:** `Plugin`, 16 methods.
- **Fix (optional):** move the descriptive metadata (`description`, `usagePattern`, `uiDesign`,
  `outputPorts`) into a `PluginDescriptor` record returned by one method.
- **Why still open:** it changes every plugin and its callers in `web` and `mcp`, so it needs its
  own change. Delete this file once it lands.
