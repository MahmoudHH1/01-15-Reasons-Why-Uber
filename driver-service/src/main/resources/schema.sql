DO $$ BEGIN
    CREATE TYPE driver_status AS ENUM ('AVAILABLE', 'BUSY', 'OFFLINE');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

DO $$ BEGIN
    CREATE TYPE document_type AS ENUM ('LICENSE', 'INSURANCE', 'REGISTRATION');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

CREATE TABLE IF NOT EXISTS drivers (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL,
    email                   VARCHAR(255) NOT NULL UNIQUE,
    phone                   VARCHAR(255) NOT NULL UNIQUE,
    license_number          VARCHAR(255) NOT NULL UNIQUE,
    status                  driver_status NOT NULL,
    rating                  DOUBLE PRECISION DEFAULT 0.0,
    total_ratings           INTEGER DEFAULT 0,
    total_completed_rides   INTEGER NOT NULL DEFAULT 0,
    total_earnings          DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    reversed_ride_ids       JSONB DEFAULT '[]'::jsonb,
    vehicle_details         JSONB,
    created_at              TIMESTAMP NOT NULL
)^^

ALTER TABLE drivers ADD COLUMN IF NOT EXISTS total_completed_rides INTEGER NOT NULL DEFAULT 0^^
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS total_earnings DOUBLE PRECISION NOT NULL DEFAULT 0.0^^
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS reversed_ride_ids JSONB DEFAULT '[]'::jsonb^^

CREATE TABLE IF NOT EXISTS driver_documents (
    id              BIGSERIAL PRIMARY KEY,
    type            document_type NOT NULL,
    document_url    VARCHAR(255) NOT NULL,
    expiry_date     DATE NOT NULL,
    verified        BOOLEAN DEFAULT false,
    metadata        JSONB,
    uploaded_at     TIMESTAMP NOT NULL,
    driver_id       BIGINT NOT NULL REFERENCES drivers(id)
)^^
