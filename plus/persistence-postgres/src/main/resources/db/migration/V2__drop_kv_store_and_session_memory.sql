-- SPDX-License-Identifier: AGPL-3.0-only
-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
--
-- KV_STORE and SESSION_MEMORY were removed: no built-in plugin keeps data between runs or in this
-- database (design/plans/community-plugins.md). Their rows are deleted with the tables.
DROP TABLE IF EXISTS kv_entry;
DROP TABLE IF EXISTS session_history;
