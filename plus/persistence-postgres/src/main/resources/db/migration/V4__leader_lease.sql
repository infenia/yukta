-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- Which server runs workflows when several share the database: the holder renews the lease
-- before it expires, another server takes it over once it has. The fencing number grows with every
-- change of holder.
CREATE TABLE leader_lease (
    name       TEXT        PRIMARY KEY,
    holder     TEXT        NOT NULL,
    fencing    BIGINT      NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
