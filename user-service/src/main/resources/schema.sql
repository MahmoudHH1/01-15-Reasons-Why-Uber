DO $$ BEGIN
    CREATE TYPE user_role AS ENUM ('RIDER', 'ADMIN');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

DO $$ BEGIN
    CREATE TYPE user_status AS ENUM ('ACTIVE', 'DEACTIVATED');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    phone       VARCHAR(255) NOT NULL UNIQUE,
    role        user_role    NOT NULL,
    status      user_status  NOT NULL DEFAULT 'ACTIVE',
    preferences JSONB,
    created_at  TIMESTAMP    NOT NULL
    )^^

CREATE TABLE IF NOT EXISTS saved_addresses (
    id          BIGSERIAL PRIMARY KEY,
    label       VARCHAR(255) NOT NULL,
    address     TEXT NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT false,
    metadata    JSONB,
    created_at  TIMESTAMP    NOT NULL,
    user_id     BIGINT       NOT NULL REFERENCES users(id)
    )^^