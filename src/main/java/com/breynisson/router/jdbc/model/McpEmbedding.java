package com.breynisson.router.jdbc.model;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.pgvector.PGvector;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class McpEmbedding {

    public final String filePath;
    public final int chunkIndex;
    public final String sourceUrl;
    public final String chunkText;
    public final float[] embedding;
    public final String model;
    public final String indexedAt;

    public McpEmbedding(String filePath, int chunkIndex, String sourceUrl, String chunkText,
                         float[] embedding, String model, String indexedAt) {
        this.filePath = filePath;
        this.chunkIndex = chunkIndex;
        this.sourceUrl = sourceUrl;
        this.chunkText = chunkText;
        this.embedding = embedding;
        this.model = model;
        this.indexedAt = indexedAt;
    }

    public static class ResultSetTransform implements DatabaseAdapter.ResultSetTransform<McpEmbedding> {

        @Override
        public List<McpEmbedding> transform(ResultSet rset) throws SQLException {
            List<McpEmbedding> list = new ArrayList<>();
            while (rset.next()) {
                PGvector vector = (PGvector) rset.getObject(5); // EMBEDDING
                list.add(new McpEmbedding(
                        rset.getString(1),   // FILE_PATH
                        rset.getInt(2),       // CHUNK_INDEX
                        rset.getString(3),   // SOURCE_URL
                        rset.getString(4),   // CHUNK_TEXT
                        vector.toArray(),
                        null,                // MODEL not needed for search
                        null));              // INDEXED_AT not needed for search
            }
            return list;
        }
    }
}
