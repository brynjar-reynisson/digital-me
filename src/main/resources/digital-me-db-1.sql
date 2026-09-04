-- This shared database (Supabase local dev stack, also serving agent-suite
-- and soulman) already has a conventional "extensions" schema for exactly
-- this purpose — install pgvector there once rather than into this
-- table's own "digitalme" schema, and reference the type schema-qualified
-- below so it resolves correctly regardless of this schema's search_path.
CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions;

CREATE TABLE APPLICATION_METADATA (
    KEY VARCHAR(1024) PRIMARY KEY NOT NULL,
    VALUE TEXT
);

CREATE TABLE TEXT_ENTRY (
    UUID VARCHAR(60) PRIMARY KEY NOT NULL,
    TIME VARCHAR(23) NOT NULL,
    NAME TEXT NOT NULL
);

CREATE TABLE TEXT_ENTRY_METADATA (
    TEXT_ENTRY_UUID VARCHAR(60) NOT NULL,
    KEY VARCHAR(1024) NOT NULL,
    VALUE TEXT,
    PRIMARY KEY (TEXT_ENTRY_UUID, KEY)
);

CREATE TABLE MCP_EMBEDDING (
    FILE_PATH   TEXT NOT NULL,
    CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
    SOURCE_URL  TEXT NOT NULL,
    CHUNK_TEXT  TEXT NOT NULL,
    EMBEDDING   extensions.VECTOR(768) NOT NULL,
    MODEL       TEXT NOT NULL,
    INDEXED_AT  TEXT NOT NULL,
    PRIMARY KEY (FILE_PATH, CHUNK_INDEX)
);
CREATE INDEX mcp_embedding_hnsw_idx ON MCP_EMBEDDING
    USING hnsw (EMBEDDING extensions.vector_cosine_ops);

CREATE TABLE SUMMARY_CACHE (
    SOURCE_URL TEXT NOT NULL PRIMARY KEY,
    SUMMARY    TEXT NOT NULL,
    CREATED_AT TEXT NOT NULL
);

CREATE TABLE ADD_CONTENT_QUEUE (
    UUID        VARCHAR(60) NOT NULL PRIMARY KEY,
    PAYLOAD     TEXT        NOT NULL,
    RECEIVED_AT TEXT        NOT NULL,
    ATTEMPTS    INTEGER     NOT NULL DEFAULT 0
);

INSERT INTO APPLICATION_METADATA (KEY, VALUE) VALUES ('database.version', '1');
