-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: Apache-2.0

-- A note on a saved workflow version.
ALTER TABLE workflow_definition_revision ADD COLUMN note TEXT;
