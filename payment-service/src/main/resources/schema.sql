DO $$ BEGIN
    CREATE TYPE payment_method AS ENUM ('CREDIT_CARD', 'CASH', 'WALLET');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

DO $$ BEGIN
    CREATE TYPE payment_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

DO $$ BEGIN
    CREATE TYPE discount_type AS ENUM ('PERCENTAGE', 'FIXED');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$^^

CREATE TABLE IF NOT EXISTS payments (
    id                    BIGSERIAL PRIMARY KEY,
    ride_id               BIGINT NOT NULL,
    user_id               BIGINT NOT NULL,
    amount                DOUBLE PRECISION NOT NULL,
    method                payment_method NOT NULL,
    status                payment_status NOT NULL,
    transaction_details   JSONB,
    created_at            TIMESTAMP NOT NULL
)^^

CREATE TABLE IF NOT EXISTS coupons (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(255) NOT NULL UNIQUE,
    discount_type   discount_type NOT NULL,
    discount_value  DOUBLE PRECISION NOT NULL,
    max_uses        INTEGER NOT NULL,
    current_uses    INTEGER DEFAULT 0,
    expiry_date     TIMESTAMP NOT NULL,
    active          BOOLEAN DEFAULT true,
    metadata        JSONB
)^^

CREATE TABLE IF NOT EXISTS payment_coupons (
    id                 BIGSERIAL PRIMARY KEY,
    discount_applied   DOUBLE PRECISION NOT NULL,
    applied_at         TIMESTAMP NOT NULL,
    payment_id         BIGINT NOT NULL REFERENCES payments(id),
    coupon_id          BIGINT NOT NULL REFERENCES coupons(id)
)^^
