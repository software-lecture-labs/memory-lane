-- Switches users, memories and photos to UUID keys and adds retry (idempotency) columns.
-- V1 is left untouched because it is already applied in local databases.
-- No API writes these tables yet, so they are recreated instead of converted in place;
-- the guard below refuses to drop any existing rows.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users) OR EXISTS (SELECT 1 FROM sessions)
            OR EXISTS (SELECT 1 FROM memories) OR EXISTS (SELECT 1 FROM photos) THEN
        RAISE EXCEPTION 'V2 recreates users, sessions, memories and photos with UUID keys and needs them empty. '
            'Reset the local database (docker compose down -v) and start the application again.';
    END IF;
END $$;

DROP TABLE photos;
DROP TABLE memories;
DROP TABLE sessions;
DROP TABLE users;

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email)))
);

-- Only a hash of the opaque bearer token is stored.
CREATE TABLE sessions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id),
    token_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_sessions_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_sessions_user_id ON sessions (user_id);

-- memory_date is a plain DATE so the day is not shifted by time zone conversion.
-- A create retry with the same client_request_id returns the existing memory when
-- client_request_sha256 (hash of the create request) matches, otherwise it is a conflict.
CREATE TABLE memories (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id              UUID          NOT NULL REFERENCES users (id),
    client_request_id     UUID          NOT NULL,
    client_request_sha256 CHAR(64)      NOT NULL,
    title                 VARCHAR(100)  NOT NULL,
    memory_date           DATE          NOT NULL,
    description           VARCHAR(3000),
    category              VARCHAR(20)   NOT NULL DEFAULT 'OTHER',
    mood                  VARCHAR(20),
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_memories_owner_client_request UNIQUE (owner_id, client_request_id),
    CONSTRAINT ck_memories_client_request_sha256 CHECK (client_request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_memories_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_memories_category CHECK (category IN ('TRAVEL', 'FAMILY', 'FRIENDS', 'SCHOOL', 'DAILY_LIFE', 'OTHER')),
    CONSTRAINT ck_memories_mood CHECK (mood IN ('HAPPY', 'PEACEFUL', 'EXCITED', 'NOSTALGIC', 'SAD')),
    CONSTRAINT ck_memories_location_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_memories_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_memories_longitude CHECK (longitude BETWEEN -180 AND 180)
);
-- Timeline order: newest day first, id as stable tie-breaker.
CREATE INDEX ix_memories_owner_timeline ON memories (owner_id, memory_date DESC, id DESC);

-- The cover is the photo with the smallest existing position; positions are not renumbered,
-- so deleting the cover makes the next photo the cover and new photos get max(position) + 1.
-- The 5-photos-per-memory limit is enforced by the service while it holds a lock on the memory row.
-- An upload retry with the same client_photo_id returns the existing photo when content_sha256
-- matches, otherwise it is a conflict.
CREATE TABLE photos (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    memory_id       UUID         NOT NULL REFERENCES memories (id),
    client_photo_id UUID         NOT NULL,
    content_sha256  CHAR(64)     NOT NULL,
    storage_key     VARCHAR(255) NOT NULL,
    media_type      VARCHAR(20)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    position        INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_photos_storage_key UNIQUE (storage_key),
    CONSTRAINT uq_photos_memory_client_photo UNIQUE (memory_id, client_photo_id),
    CONSTRAINT uq_photos_memory_position UNIQUE (memory_id, position),
    CONSTRAINT ck_photos_content_sha256 CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_photos_media_type CHECK (media_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_photos_size CHECK (size_bytes > 0 AND size_bytes <= 5000000),
    CONSTRAINT ck_photos_position CHECK (position >= 0)
);
