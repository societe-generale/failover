package com.societegenerale.failover.store.handler;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("should return VARCHAR as the payload SQL type")
    void payloadTypeReturnsVarchar() {
        assertThat(handler.payloadType()).isEqualTo(Types.VARCHAR);
    }

    @Test
    @DisplayName("should extract and return the string payload from the result set")
    void extractPayloadReturnsStringFromResultSet() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn(JSON_PAYLOAD);

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isEqualTo(JSON_PAYLOAD);
        verify(resultSet).getString(PAYLOAD_COLUMN_NAME);
    }

    @Test
    @DisplayName("should return null when the result set column value is null")
    void extractPayloadNullValueReturnsNull() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn(null);

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should return empty string when the result set column value is empty")
    void extractPayloadEmptyStringReturnsEmpty() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn("");

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return whitespace string as-is without trimming")
    void extractPayloadWhitespaceStringReturnsAsIs() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenReturn("   ");

        String result = handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME);

        assertThat(result).isEqualTo("   ");
    }

    @Test
    @DisplayName("should use the exact column name provided to query the result set")
    void extractPayloadUsesExactColumnName() throws SQLException {
        when(resultSet.getString("MY_CUSTOM_COL")).thenReturn("data");

        String result = handler.extractPayload(resultSet, "MY_CUSTOM_COL");

        assertThat(result).isEqualTo("data");
        verify(resultSet).getString("MY_CUSTOM_COL");
        verify(resultSet, never()).getString(PAYLOAD_COLUMN_NAME);
    }

    @Test
    @DisplayName("should propagate SQLException thrown by the result set")
    void extractPayloadSqlExceptionPropagates() throws SQLException {
        when(resultSet.getString(PAYLOAD_COLUMN_NAME)).thenThrow(new SQLException("column not found"));
        assertThrows(SQLException.class, () -> handler.extractPayload(resultSet, PAYLOAD_COLUMN_NAME));
    }
}