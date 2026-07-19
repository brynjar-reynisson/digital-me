package com.breynisson.router.jdbc.model;

import com.breynisson.router.jdbc.DatabaseAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class AddContentQueueEntry {

    public final String uuid;
    public final String payload;
    public final String receivedAt;
    public final int attempts;

    public AddContentQueueEntry(String uuid, String payload, String receivedAt, int attempts) {
        this.uuid = uuid;
        this.payload = payload;
        this.receivedAt = receivedAt;
        this.attempts = attempts;
    }

    public static class ResultSetTransform implements DatabaseAdapter.ResultSetTransform<AddContentQueueEntry> {

        @Override
        public List<AddContentQueueEntry> transform(ResultSet rset) throws SQLException {
            List<AddContentQueueEntry> list = new ArrayList<>();
            while (rset.next()) {
                list.add(new AddContentQueueEntry(
                        rset.getString(1),  // UUID
                        rset.getString(2),  // PAYLOAD
                        rset.getString(3),  // RECEIVED_AT
                        rset.getInt(4)));   // ATTEMPTS
            }
            return list;
        }
    }
}
