CREATE TABLE IF NOT EXISTS services (
    account_id   TEXT        NOT NULL,
    service_name TEXT        NOT NULL,
    is_public    BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, service_name)
);

-- Composite PK is the exactly-once guarantee.
-- INSERT ON CONFLICT DO NOTHING is the atomic deduplication guard.
CREATE TABLE IF NOT EXISTS seen_triples (
    account_id     TEXT        NOT NULL,
    source         TEXT        NOT NULL,
    destination    TEXT        NOT NULL,
    classification TEXT        NOT NULL,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, source, destination, classification)
);
