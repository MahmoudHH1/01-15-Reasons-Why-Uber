DO $$ BEGIN
CREATE TYPE ride_status AS ENUM (
        'REQUESTED', 'ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED',
        'PAYMENT_PENDING', 'PAID', 'PAYMENT_FAILED', 'REFUNDED'
    );
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

DO $$ BEGIN
CREATE TYPE ride_stop_status AS ENUM ('PENDING', 'REACHED', 'SKIPPED');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

CREATE TABLE IF NOT EXISTS rides (
                                     id                BIGSERIAL PRIMARY KEY,
                                     user_id           BIGINT NOT NULL,
                                     driver_id         BIGINT,
                                     pickup_latitude   DOUBLE PRECISION NOT NULL,
                                     pickup_longitude  DOUBLE PRECISION NOT NULL,
                                     dropoff_latitude  DOUBLE PRECISION NOT NULL,
                                     dropoff_longitude DOUBLE PRECISION NOT NULL,
                                     status            VARCHAR(50) NOT NULL,
    fare              DOUBLE PRECISION,
    metadata          JSONB,
    requested_at      TIMESTAMP NOT NULL,
    completed_at      TIMESTAMP
    )^^

CREATE TABLE IF NOT EXISTS ride_stops (
                                          id          BIGSERIAL PRIMARY KEY,
                                          stop_order  INTEGER NOT NULL,
                                          latitude    DOUBLE PRECISION NOT NULL,
                                          longitude   DOUBLE PRECISION NOT NULL,
                                          address     TEXT NOT NULL,
                                          status      ride_stop_status NOT NULL,
                                          metadata    JSONB,
                                          ride_id     BIGINT NOT NULL REFERENCES rides(id)
    )^^