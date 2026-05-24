package com.societegenerale.failover.store.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VarcharPayloadColumnHandlerTest {

    private static final String PAYLOAD_COLUMN_NAME = "PAYLOAD";
    private static final String JSON_PAYLOAD = "{\"key\":\"some-value\"}";

    @Mock
    private ResultSet resultSet;

    private final VarcharPayloadColumnHandler handler = new VarcharPayloadColumnHandler();

    @Test
    void payloadType_returnsVarchar() {
        assertThat(handler.payloadType()).isEqualTo(Types.VARCHAR);
    }

    @Test
    void extractPayload_returnsStringFromResultSet() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn(JSON_PAYLOAD);

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isEqualTo(JSON_PAYLOAD);
        verify(resultSet).getString(PAYLOAD_COLUMN_NAME);
    }

    @Test
    void extractPayload_nullValue_returnsNull() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn(null);

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isNull();
    }

    @Test
    void extractPayload_emptyString_returnsEmpty() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn("");

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isEmpty();
    }

    @Test
    void extractPayload_whitespaceString_returnsAsIs() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn("   ");

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isEqualTo("   ");
    }

    @Test
    void extractPayload_usesExactColumnName() throws SQLException {
        when(resultSet.getString("MY_CUSTOM_COL")).thenReturn("data");

        String result = handler.extractPayload(resultSet, "MY_CUSTOM_COL");

        assertThat(result).isEqualTo("data");
        verify(resultSet).getString("MY_CUSTOM_COL");
        verify(resultSet, never()).getString(PAYLOAD_COLUMN_NAME);
    }

    @Test
    void extractPayload_sqlException_propagates() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenThrow(new SQLException("column not found"));
        assertThrows(SQLException.class, () -> handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME));
    }
}