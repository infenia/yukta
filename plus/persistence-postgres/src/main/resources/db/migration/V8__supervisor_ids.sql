-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- Supervisor ids follow [A-Za-z0-9._-]{1,64}: no ':' and no whitespace, so an id never looks like
-- an actor of another kind (key:<name>, token:<tier>). Rename the stored ids that break the rule
-- and keep the old to new mapping, so an operator can find a renamed supervisor.
CREATE TABLE supervisor_id_migration (
    kind        TEXT        NOT NULL,
    old_id      TEXT        NOT NULL,
    new_id      TEXT        NOT NULL,
    migrated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, old_id)
);

DO $$
DECLARE
    rec       RECORD;
    base      TEXT;
    candidate TEXT;
    suffix    TEXT;
    n         INT;
BEGIN
    -- profiles first, then bindings; each kind has its own id space
    FOR rec IN
        SELECT 'profile' AS kind, id FROM supervisor_profile
         WHERE id !~ '^[A-Za-z0-9._-]{1,64}$'
        UNION ALL
        SELECT 'binding', id FROM supervisor_binding
         WHERE id !~ '^[A-Za-z0-9._-]{1,64}$'
        ORDER BY 1 DESC, 2
    LOOP
        base := left(regexp_replace(rec.id, '[^A-Za-z0-9._-]', '_', 'g'), 64);
        IF base = '' THEN
            base := '_';
        END IF;
        candidate := base;
        n := 2;
        WHILE (rec.kind = 'profile' AND EXISTS (SELECT 1 FROM supervisor_profile WHERE id = candidate))
           OR (rec.kind = 'binding' AND EXISTS (SELECT 1 FROM supervisor_binding WHERE id = candidate))
           OR EXISTS (SELECT 1 FROM supervisor_id_migration
                       WHERE kind = rec.kind AND new_id = candidate)
        LOOP
            suffix := '-' || n;
            candidate := left(base, 64 - length(suffix)) || suffix;
            n := n + 1;
        END LOOP;
        INSERT INTO supervisor_id_migration (kind, old_id, new_id)
        VALUES (rec.kind, rec.id, candidate);
    END LOOP;

    IF EXISTS (SELECT 1 FROM supervisor_id_migration) THEN
        ALTER TABLE supervisor_binding DROP CONSTRAINT supervisor_binding_profile_id_fkey;

        UPDATE supervisor_profile p SET id = m.new_id
          FROM supervisor_id_migration m
         WHERE m.kind = 'profile' AND p.id = m.old_id;

        UPDATE supervisor_binding b SET profile_id = m.new_id
          FROM supervisor_id_migration m
         WHERE m.kind = 'profile' AND b.profile_id = m.old_id;

        UPDATE supervisor_binding b SET id = m.new_id
          FROM supervisor_id_migration m
         WHERE m.kind = 'binding' AND b.id = m.old_id;

        ALTER TABLE supervisor_binding
            ADD CONSTRAINT supervisor_binding_profile_id_fkey
            FOREIGN KEY (profile_id) REFERENCES supervisor_profile (id);
    END IF;
END
$$;
