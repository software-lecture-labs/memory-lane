-- Least-privilege runtime role for the shared Supabase database (run as the schema owner, postgres).
-- The application connects as memorylane_app; the owner account is used only to apply migrations.
-- Not a Flyway migration: roles are cluster-wide and CI/Testcontainers databases do not need it.
-- Idempotent: safe to run again. The password is set separately and is never stored in the repo:
--   ALTER ROLE memorylane_app PASSWORD '<güçlü parola>';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'memorylane_app') THEN
        CREATE ROLE memorylane_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END $$;

-- Row access only: no CREATE/ALTER/DROP/TRUNCATE, no access to flyway_schema_history.
GRANT USAGE ON SCHEMA memorylane TO memorylane_app;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON memorylane.users, memorylane.sessions, memorylane.memories, memorylane.photos,
       memorylane.file_cleanup_jobs
    TO memorylane_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA memorylane TO memorylane_app;

-- Tables and sequences created by later migrations (run as postgres) get the same row access.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA memorylane
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO memorylane_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA memorylane
    GRANT USAGE ON SEQUENCES TO memorylane_app;

-- Safety limits: 4 developers x pool size 3 plus headroom; stop runaway or stuck queries.
ALTER ROLE memorylane_app CONNECTION LIMIT 20;
ALTER ROLE memorylane_app SET search_path = memorylane;
ALTER ROLE memorylane_app SET statement_timeout = '30s';
ALTER ROLE memorylane_app SET idle_in_transaction_session_timeout = '60s';
