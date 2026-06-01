/*
 * Copyright 2022-2023, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.store.mapper;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.store.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.serializer.Serializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferentialPayloadRowMapperTest {

    private static final String FAILOVER_NAME   = "myReferential";
    private static final String FAILOVER_KEY    = "key-123";
    private static final LocalDateTime AS_OF     = LocalDateTime.of(2024, 1, 10, 8, 0);
    private static final LocalDateTime EXPIRE_ON = LocalDateTime.of(2024, 1, 11, 8, 0);
    private static final String PAYLOAD_CLASS   = "com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapperTest$SamplePayload";
    private static final String PAYLOAD_JSON    = "{\"label\":\"hello\"}";

    @Mock
    private ResultSet resultSet;

    @Mock
    private PayloadColumnResolver payloadColumnResolver;

    @Mock
    private Serializer serializer;

    private ReferentialPayloadRowMapper<SamplePayload> mapper;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class SamplePayload {
        private String label;
    }

    @BeforeEach
    void setUp() {
        mapper = new ReferentialPayloadRowMapper<>(payloadColumnResolver, serializer);
    }

    private void stubHappyPath() throws SQLException {
        when(resultSet.getString("FAILOVER_NAME")).thenReturn(FAILOVER_NAME);
        when(resultSet.getString("FAILOVER_KEY")).thenReturn(FAILOVER_KEY);
        when(resultSet.getTimestamp("AS_OF")).thenReturn(Timestamp.valueOf(AS_OF));
        when(resultSet.getTimestamp("EXPIRE_ON")).thenReturn(Timestamp.valueOf(EXPIRE_ON));
        when(resultSet.getString("PAYLOAD_CLASS")).thenReturn(PAYLOAD_CLASS);
        when(payloadColumnResolver.extractPayload(resultSet, "PAYLOAD")).thenReturn(PAYLOAD_JSON);
        when(serializer.toClass(PAYLOAD_CLASS)).thenReturn((Class) SamplePayload.class);
        when(serializer.deserialize(PAYLOAD_JSON, SamplePayload.class)).thenReturn(new SamplePayload("hello"));
    }

    @Nested
    @DisplayName("mapRow — happy path")
    class HappyPath {

        @Test
        @DisplayName("should map all columns to a ReferentialPayload")
        void mapsAllColumnsCorrectly() throws SQLException {
            stubHappyPath();

            ReferentialPayload<SamplePayload> result = mapper.mapRow(resultSet, 0);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo(FAILOVER_NAME);
            assertThat(result.getKey()).isEqualTo(FAILOVER_KEY);
            assertThat(result.getAsOf()).isEqualTo(AS_OF);
            assertThat(result.getExpireOn()).isEqualTo(EXPIRE_ON);
            assertThat(result.getPayload()).isEqualTo(new SamplePayload("hello"));
        }

        @Test
        @DisplayName("should always set upToDate to false")
        void upToDateIsAlwaysFalse() throws SQLException {
            stubHappyPath();

            ReferentialPayload<SamplePayload> result = mapper.mapRow(resultSet, 0);

            assertThat(result).isNotNull();
            assertThat(result.isUpToDate()).isFalse();
        }

        @Test
        @DisplayName("should extract PAYLOAD via PayloadColumnResolver with column name 'PAYLOAD'")
        void delegatesPayloadExtractionToResolver() throws SQLException {
            stubHappyPath();

            mapper.mapRow(resultSet, 0);

            verify(payloadColumnResolver).extractPayload(resultSet, "PAYLOAD");
        }

        @Test
        @DisplayName("should resolve payload class via Serializer.toClass")
        void delegatesClassResolutionToSerializer() throws SQLException {
            stubHappyPath();

            mapper.mapRow(resultSet, 0);

            verify(serializer).toClass(PAYLOAD_CLASS);
        }

        @Test
        @DisplayName("should deserialize payload via Serializer.deserialize")
        void delegatesDeserializationToSerializer() throws SQLException {
            stubHappyPath();

            mapper.mapRow(resultSet, 0);

            verify(serializer).deserialize(PAYLOAD_JSON, SamplePayload.class);
        }

        @Test
        @DisplayName("rowNum parameter does not affect mapping result")
        void rowNumIsIgnored() throws SQLException {
            stubHappyPath();
            ReferentialPayload<SamplePayload> r1 = mapper.mapRow(resultSet, 0);

            stubHappyPath();
            ReferentialPayload<SamplePayload> r2 = mapper.mapRow(resultSet, 99);

            assertThat(r1).isEqualTo(r2);
        }
    }

    @Nested
    @DisplayName("mapRow — null payload")
    class NullPayload {

        @Test
        @DisplayName("should return ReferentialPayload with null payload when deserializer returns null")
        void nullPayloadMappedCorrectly() throws SQLException {
            when(resultSet.getString("FAILOVER_NAME")).thenReturn(FAILOVER_NAME);
            when(resultSet.getString("FAILOVER_KEY")).thenReturn(FAILOVER_KEY);
            when(resultSet.getTimestamp("AS_OF")).thenReturn(Timestamp.valueOf(AS_OF));
            when(resultSet.getTimestamp("EXPIRE_ON")).thenReturn(Timestamp.valueOf(EXPIRE_ON));
            when(resultSet.getString("PAYLOAD_CLASS")).thenReturn(PAYLOAD_CLASS);
            when(payloadColumnResolver.extractPayload(resultSet, "PAYLOAD")).thenReturn(null);
            when(serializer.toClass(PAYLOAD_CLASS)).thenReturn((Class) SamplePayload.class);
            when(serializer.deserialize(null, SamplePayload.class)).thenReturn(null);

            ReferentialPayload<SamplePayload> result = mapper.mapRow(resultSet, 0);

            assertThat(result).isNotNull();
            assertThat(result.getPayload()).isNull();
        }
    }

    @Nested
    @DisplayName("mapRow — corrupt row guards")
    class CorruptRowGuards {

        @Test
        @DisplayName("should throw FailoverStoreException when AS_OF is null")
        void asOfNullThrowsException() throws SQLException {
            when(resultSet.getString("FAILOVER_NAME")).thenReturn(FAILOVER_NAME);
            when(resultSet.getString("FAILOVER_KEY")).thenReturn(FAILOVER_KEY);
            when(resultSet.getTimestamp("AS_OF")).thenReturn(null);
            when(resultSet.getTimestamp("EXPIRE_ON")).thenReturn(Timestamp.valueOf(EXPIRE_ON));

            assertThatThrownBy(() -> mapper.mapRow(resultSet, 0))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(FAILOVER_KEY);
        }

        @Test
        @DisplayName("should throw FailoverStoreException when EXPIRE_ON is null")
        void expireOnNullThrowsException() throws SQLException {
            when(resultSet.getString("FAILOVER_NAME")).thenReturn(FAILOVER_NAME);
            when(resultSet.getString("FAILOVER_KEY")).thenReturn(FAILOVER_KEY);
            when(resultSet.getTimestamp("AS_OF")).thenReturn(Timestamp.valueOf(AS_OF));
            when(resultSet.getTimestamp("EXPIRE_ON")).thenReturn(null);

            assertThatThrownBy(() -> mapper.mapRow(resultSet, 0))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(FAILOVER_KEY);
        }

        @Test
        @DisplayName("should throw FailoverStoreException when both AS_OF and EXPIRE_ON are null")
        void bothTimestampsNullThrowsException() throws SQLException {
            when(resultSet.getString("FAILOVER_NAME")).thenReturn(FAILOVER_NAME);
            when(resultSet.getString("FAILOVER_KEY")).thenReturn(FAILOVER_KEY);
            when(resultSet.getTimestamp("AS_OF")).thenReturn(null);
            when(resultSet.getTimestamp("EXPIRE_ON")).thenReturn(null);

            assertThatThrownBy(() -> mapper.mapRow(resultSet, 0))
                    .isInstanceOf(FailoverStoreException.class);
        }

        @Test
        @DisplayName("should propagate SQLException thrown by ResultSet")
        void sqlExceptionPropagates() throws SQLException {
            when(resultSet.getString("FAILOVER_NAME")).thenThrow(new SQLException("connection lost"));

            assertThatThrownBy(() -> mapper.mapRow(resultSet, 0))
                    .isInstanceOf(SQLException.class)
                    .hasMessage("connection lost");
        }
    }
}