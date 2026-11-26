-- Version of each memory for the detail ETag and PUT If-Match (docs/api-contract.md, "Eşzamanlı
-- güncelleme"). Every change to what GET /memories/{id} returns (text fields, photos) increments it
-- in the same transaction. It is server-only: no memory or photo DTO exposes or accepts it.
ALTER TABLE memories
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_memories_version_positive CHECK (version >= 1);
