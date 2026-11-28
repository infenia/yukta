-- The history behind the activity feed: every event of the live stream except log lines, newest
-- by seq. Trimmed by age and by row count when entries are appended.
CREATE TABLE activity (
    seq          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at           TIMESTAMPTZ NOT NULL,
    type         TEXT        NOT NULL,
    severity     TEXT        NOT NULL,
    execution_id TEXT        NOT NULL,
    envelope     JSONB       NOT NULL
);

CREATE INDEX activity_at ON activity (at);
