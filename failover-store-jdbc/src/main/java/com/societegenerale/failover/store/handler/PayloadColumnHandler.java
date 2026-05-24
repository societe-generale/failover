package com.societegenerale.failover.store.handler;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface PayloadColumnHandler {

    /**
     * @return the type of payload column ( refer java.sql.Types class for more details )
     */
    int payloadType();

    /**
     * @param resultSet : result set of payload row
     * @param payloadColumn : payload column name
     * @return : the payload as String from payload column
     */
    String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException;
}
