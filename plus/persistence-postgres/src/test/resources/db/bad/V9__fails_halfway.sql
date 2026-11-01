-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- A broken migration for MigrationUpgradeTest: it changes the schema, then fails.
CREATE TABLE half_done (id INT);
ALTER TABLE workflow_definition ADD COLUMN broken NOT_A_TYPE;
