-- Makes delete behaviour explicit (ADR-0002): rows with dependants cannot be deleted, so
-- services must remove photos, memories and sessions in order within one transaction.
ALTER TABLE sessions
    DROP CONSTRAINT sessions_user_id_fkey,
    ADD CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE memories
    DROP CONSTRAINT memories_owner_id_fkey,
    ADD CONSTRAINT fk_memories_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE photos
    DROP CONSTRAINT photos_memory_id_fkey,
    ADD CONSTRAINT fk_photos_memory FOREIGN KEY (memory_id) REFERENCES memories (id) ON DELETE RESTRICT;

-- Photo order is upload order in the first release. DEFERRABLE keeps a later reorder feature
-- possible: a transaction may run SET CONSTRAINTS uq_photos_memory_position DEFERRED and swap
-- positions; uniqueness is still checked immediately by default.
ALTER TABLE photos
    DROP CONSTRAINT uq_photos_memory_position,
    ADD CONSTRAINT uq_photos_memory_position UNIQUE (memory_id, position) DEFERRABLE INITIALLY IMMEDIATE;
