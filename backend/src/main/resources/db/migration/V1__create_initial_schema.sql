-- Initial schema from the project data model (users, sessions, memories, photos, file cleanup).
-- Never edit an applied migration; add a new V<n>__description.sql file instead.

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email)))
);

-- Only a hash of the opaque bearer token is stored.
CREATE TABLE sessions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    token_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_sessions_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_sessions_user_id ON sessions (user_id);

-- memory_date is a plain DATE so the day is not shifted by time zone conversion.
CREATE TABLE memories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id    BIGINT        NOT NULL REFERENCES users (id),
    title       VARCHAR(100)  NOT NULL,
    memory_date DATE          NOT NULL,
    description VARCHAR(3000),
    category    VARCHAR(20)   NOT NULL DEFAULT 'OTHER',
    mood        VARCHAR(20),
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_memories_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_memories_category CHECK (category IN ('TRAVEL', 'FAMILY', 'FRIENDS', 'SCHOOL', 'DAILY_LIFE', 'OTHER')),
    CONSTRAINT ck_memories_mood CHECK (mood IN ('HAPPY', 'PEACEFUL', 'EXCITED', 'NOSTALGIC', 'SAD')),
    CONSTRAINT ck_memories_location_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_memories_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_memories_longitude CHECK (longitude BETWEEN -180 AND 180)
);
-- Timeline order: newest day first, id as stable tie-breaker.
CREATE INDEX ix_memories_owner_timeline ON memories (owner_id, memory_date DESC, id DESC);

-- position 0 is the cover photo; at most 5 photos per memory.
CREATE TABLE photos (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    memory_id       BIGINT       NOT NULL REFERENCES memories (id),
    client_photo_id UUID         NOT NULL,
    storage_key     VARCHAR(255) NOT NULL,
    media_type      VARCHAR(20)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    position        SMALLINT     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_photos_storage_key UNIQUE (storage_key),
    CONSTRAINT uq_photos_memory_client_photo UNIQUE (memory_id, client_photo_id),
    CONSTRAINT uq_photos_memory_position UNIQUE (memory_id, position),
    CONSTRAINT ck_photos_media_type CHECK (media_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_photos_size CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT ck_photos_position CHECK (position BETWEEN 0 AND 4)
);

-- Written in the same transaction as a delete; files are removed after commit and retried on failure.
CREATE TABLE file_cleanup_jobs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_key     VARCHAR(255) NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_file_cleanup_jobs_attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_file_cleanup_jobs_next_attempt ON file_cleanup_jobs (next_attempt_at);
