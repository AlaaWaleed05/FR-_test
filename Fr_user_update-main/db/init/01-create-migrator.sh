#!/bin/bash
set -euo pipefail

# Runs once, at first container init, authenticated as the bootstrap superuser
# (POSTGRES_USER). Creates fru_migrator as a plain login role — CREATEROLE so it can
# later create fru_app in V0001, but deliberately NOT superuser, so that the audit
# triggers proven in step 7 are shown blocking an owner, not a superuser bypass.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE ROLE fru_migrator LOGIN CREATEROLE PASSWORD '$FRU_MIGRATOR_PASSWORD';
	ALTER DATABASE "$POSTGRES_DB" OWNER TO fru_migrator;
EOSQL
