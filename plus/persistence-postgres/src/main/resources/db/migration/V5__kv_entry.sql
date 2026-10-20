-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The state of KV_STORE nodes when yukta.kv.store-type=postgres.
CREATE TABLE kv_entry (
    k TEXT  PRIMARY KEY,
    v JSONB NOT NULL
);
