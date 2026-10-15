-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: Apache-2.0

-- The state of KV_STORE nodes when yukta.kv.store-type=postgres.
CREATE TABLE kv_entry (
    k TEXT  PRIMARY KEY,
    v JSONB NOT NULL
);
