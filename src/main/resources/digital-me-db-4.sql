-- Collapse to the single bge-m3 embedding table.
-- Drops the obsolete nomic-embed-text table (with its HNSW and unmigrated indexes and primary key),
-- then renames the 1024-dimension table and its indexes into the original names.
-- Deliberately no IF EXISTS on the rename: re-applying this script to an already-migrated schema
-- must fail so the surrounding transaction rolls back instead of dropping the new table.
DROP TABLE MCP_EMBEDDING;

ALTER TABLE MCP_EMBEDDING_V2 RENAME TO MCP_EMBEDDING;
ALTER INDEX mcp_embedding_v2_pkey RENAME TO mcp_embedding_pkey;
ALTER INDEX mcp_embedding_v2_hnsw_idx RENAME TO mcp_embedding_hnsw_idx;
ALTER INDEX mcp_embedding_v2_source_idx RENAME TO mcp_embedding_source_idx;

INSERT INTO APPLICATION_METADATA (KEY, VALUE) VALUES ('database.version', '4')
ON CONFLICT (KEY) DO UPDATE SET VALUE = '4';
