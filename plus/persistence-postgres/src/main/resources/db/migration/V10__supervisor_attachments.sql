-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- Profiles become supervisors and bindings become attachments. An attachment has a source
-- (MANUAL, DEFINITION or SYSTEM) and, for a DEFINITION one, the session and workflow that declared
-- it. Existing rows were all made over the API, so they are MANUAL.
ALTER TABLE supervisor_profile RENAME TO supervisor;
ALTER TABLE supervisor ADD COLUMN description TEXT;

ALTER TABLE supervisor_binding RENAME TO supervisor_attachment;
ALTER TABLE supervisor_attachment RENAME COLUMN profile_id TO supervisor_id;
ALTER INDEX supervisor_binding_profile RENAME TO supervisor_attachment_supervisor;
ALTER TABLE supervisor_attachment
    ADD COLUMN source             TEXT NOT NULL DEFAULT 'MANUAL'
        CHECK (source IN ('MANUAL', 'DEFINITION', 'SYSTEM')),
    ADD COLUMN origin_session_id  TEXT,
    ADD COLUMN origin_workflow_id TEXT;

CREATE INDEX supervisor_attachment_origin ON supervisor_attachment (origin_session_id);
