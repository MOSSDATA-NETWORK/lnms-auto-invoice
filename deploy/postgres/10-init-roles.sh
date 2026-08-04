#!/bin/sh
set -eu

: "${POSTGRES_MIGRATION_PASSWORD:?POSTGRES_MIGRATION_PASSWORD is required}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD is required}"

psql --set ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=migration_password="$POSTGRES_MIGRATION_PASSWORD" \
    --set=app_password="$POSTGRES_APP_PASSWORD" <<'SQL'
CREATE ROLE auto_invoice_migrator
    LOGIN PASSWORD :'migration_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

CREATE ROLE auto_invoice_app
    LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

REVOKE ALL ON DATABASE auto_invoice FROM PUBLIC;
GRANT CONNECT ON DATABASE auto_invoice TO auto_invoice_migrator, auto_invoice_app;
GRANT TEMPORARY ON DATABASE auto_invoice TO auto_invoice_app;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO auto_invoice_migrator;
GRANT USAGE ON SCHEMA public TO auto_invoice_app;

-- V9 keeps CREATE EXTENSION IF NOT EXISTS for non-Compose environments.  The
-- privileged bootstrap creates it first so Flyway can run as a non-superuser.
CREATE EXTENSION IF NOT EXISTS btree_gist;
SQL
