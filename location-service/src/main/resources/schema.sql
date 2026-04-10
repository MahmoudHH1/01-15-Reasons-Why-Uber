CREATE TABLE IF NOT EXISTS locations (
    id          BIGSERIAL PRIMARY KEY,
    driver_id   BIGINT NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    timestamp   TIMESTAMP NOT NULL,
    metadata    JSONB
)^^
