package com.societegenerale.failover.store.resolver;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface PayloadColumnResolver {

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
