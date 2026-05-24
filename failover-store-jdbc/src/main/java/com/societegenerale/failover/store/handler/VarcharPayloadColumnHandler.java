package com.societegenerale.failover.store.handler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class VarcharPayloadColumnHandler implements PayloadColumnHandler {

    @Override
    public int payloadType() {
        return Types.VARCHAR;
    }

    @Override
    public String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException {
        return resultSet.getString(payloadColumn);
    }
}
