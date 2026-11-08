-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The source addresses an agent credential may be used from, comma-separated; empty for any.
ALTER TABLE agent_credential ADD COLUMN allowed_addresses TEXT NOT NULL DEFAULT '';
