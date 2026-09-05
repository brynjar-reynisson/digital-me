# Tooling

## Checkstyle

Enforced via `maven-checkstyle-plugin` 3.6.0 with rules in `checkstyle.xml`:

- UnusedImports, IllegalImport (`sun.*` — except `com.sun.net.httpserver` which is allowed for tests)
- EmptyCatchBlock, StringLiteralEquality, EqualsAvoidNull, FallThrough
- OneStatementPerLine, MultipleVariableDeclarations
- SimplifyBooleanExpression, SimplifyBooleanReturn

Run manually:
```bash
mvn checkstyle:check
```

## Claude Code hook

`.claude/settings.json` registers a `PostToolUse` hook that fires after every `Edit` or `Write` tool call:

1. Reads the edited file path from stdin JSON (via `python3`)
2. Skips non-Java files
3. Runs `mvn checkstyle:check -q`

Violations appear as warnings in Claude's output. Exit code 0 = clean.

Hook script: `.claude/scripts/checkstyle-hook.sh`

## Maven

`mvn` is not on PATH. Use the IntelliJ bundled Maven:

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" <goals>
```

## Postgres

digital-me uses the Supabase local dev stack's Postgres instance (also serving `agent-suite` and `soulman`), not a dedicated install of its own — it gets its own schema (`digitalme`) inside that shared `postgres` database. That instance already has `pgvector` 0.8.0 available (HNSW index support requires v0.5.0+); `DatabaseAdapter.init()` enables it once via `CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions`.

Default connection: `localhost:54322`, database `postgres`, schema `digitalme`, user `postgres`. Password resolves from the `POSTGRES_PASSWORD` environment variable, falling back to the literal `postgres` (Supabase CLI's own local-dev default) otherwise.

### One-time SQLite-to-Postgres migration

Run once, after upgrading to this version and before relying on it day to day:

```bash
java -jar target/digital-me-0.1.jar --digitalme.migrate-sqlite-path=C:\Users\Lenovo\DigitalMe\digital-me.db
```

This copies every row out of the old SQLite file into the already-schema'd Postgres database and exits — it does not start the web server, and never modifies or deletes the old SQLite file. Not safe to re-run against a non-empty Postgres database (not idempotent). The migration zero-pads any short embeddings to 768 dimensions (a no-op for real legacy data from `nomic-embed-text`, defensive against corrupt rows).
