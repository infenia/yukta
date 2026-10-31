-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- Supervisor profiles and the bindings that attach them to a scope at run time.
-- Settings may hold a secret (a model API key); they are stored as given.
CREATE TABLE supervisor_profile (
    id         TEXT        PRIMARY KEY,
    type       TEXT        NOT NULL,
    settings   JSONB       NOT NULL,
    dry_run    BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE supervisor_binding (
    id         TEXT        PRIMARY KEY,
    profile_id TEXT        NOT NULL REFERENCES supervisor_profile (id),
    scope      TEXT        NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX supervisor_binding_profile ON supervisor_binding (profile_id);
