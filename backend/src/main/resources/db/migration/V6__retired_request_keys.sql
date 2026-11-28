-- Keys of deleted memories (ADR-0002, docs/api-contract.md "Tekrar deneme"). Deleting a memory also
-- deletes its client_request_id; without this table a create retried later with the same
-- Idempotency-Key would bring the deleted memory back. The create endpoint answers such a key with
-- 409 IDEMPOTENCY_KEY_RETIRED instead.
--
-- Only the owner, the scope and the key are kept, never the memory's content. owner_id has no
-- foreign key on purpose: a RESTRICT key would block deleting a user after its memories, and
-- ADR-0002 allows no other delete rule. When account deletion is built, it must delete the user's
-- rows here in the same transaction as the rest (ADR-0002).
CREATE TABLE retired_request_keys (
    owner_id    UUID        NOT NULL,
    scope       VARCHAR(32) NOT NULL,
    request_key UUID        NOT NULL,
    retired_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_retired_request_keys PRIMARY KEY (owner_id, scope, request_key),
    CONSTRAINT ck_retired_request_keys_scope CHECK (scope IN ('MEMORY_CREATE'))
);

-- A trigger, so every way of deleting a memory retires its key, in the same transaction: a delete
-- that rolls back leaves the key usable. SET search_path keeps the function on this schema.
CREATE FUNCTION retire_memory_request_key() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT
AS $$
BEGIN
    INSERT INTO retired_request_keys (owner_id, scope, request_key)
    VALUES (OLD.owner_id, 'MEMORY_CREATE', OLD.client_request_id)
    ON CONFLICT DO NOTHING;
    RETURN OLD;
END $$;

CREATE TRIGGER trg_memories_retire_request_key
    AFTER DELETE ON memories
    FOR EACH ROW EXECUTE FUNCTION retire_memory_request_key();
