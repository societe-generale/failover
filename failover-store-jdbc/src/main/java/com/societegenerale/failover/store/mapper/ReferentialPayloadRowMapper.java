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
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;

/**
 * Spring JDBC {@link RowMapper} that converts a row from the {@code FAILOVER_STORE} table
 * into a {@link ReferentialPayload}.
 *
 * <p>Column mapping:
 * <ul>
 *   <li>{@code FAILOVER_NAME} / {@code FAILOVER_KEY} — identity fields</li>
 *   <li>{@code AS_OF} / {@code EXPIRE_ON} — temporal metadata (required; a {@code NULL} value
 *       in either column triggers a {@link com.societegenerale.failover.core.store.FailoverStoreException})</li>
 *   <li>{@code PAYLOAD_CLASS} — fully-qualified class name resolved via {@link Serializer#toClass(String)}</li>
 *   <li>{@code PAYLOAD} — serialized payload extracted by the {@link PayloadColumnResolver}
 *       and deserialized via {@link Serializer#deserialize}</li>
 * </ul>
 *
 * <p>The {@code upToDate} flag is always set to {@code false} for rows read from the store.
 *
 * @param <T> the type of the business payload
 * @author Anand Manissery
 */
@RequiredArgsConstructor
public class ReferentialPayloadRowMapper<T> implements RowMapper<ReferentialPayload<T>> {

    private final PayloadColumnResolver payloadColumnResolver;

    private final Serializer serializer;

    /**
     * Maps the current {@code ResultSet} row to a {@link ReferentialPayload}.
     *
     * @param rs     the {@code ResultSet} positioned on the current row
     * @param rowNum the row number (unused)
     * @return the mapped payload with {@code upToDate} set to {@code false}
     * @throws java.sql.SQLException if any column cannot be read
     * @throws com.societegenerale.failover.core.store.FailoverStoreException if {@code AS_OF}
     *         or {@code EXPIRE_ON} is {@code NULL}, indicating a corrupt row
     */
    @Override
    public ReferentialPayload<T> mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String failoverName = rs.getString("FAILOVER_NAME");
        String failoverKey  = rs.getString("FAILOVER_KEY");
        var asOfTs          = rs.getTimestamp("AS_OF");
        var expireOnTs      = rs.getTimestamp("EXPIRE_ON");
        if (asOfTs == null || expireOnTs == null) {
            throw new FailoverStoreException(
                    "Corrupt row: AS_OF or EXPIRE_ON is null for name='%s', key='%s'"
                            .formatted(failoverName, failoverKey));
        }
        var asOf            = asOfTs.toInstant();
        var expireOn        = expireOnTs.toInstant();
        String payloadClass = rs.getString("PAYLOAD_CLASS");
        Class<T> clazz = serializer.toClass(payloadClass); //cast(forName(payloadClass));
        T payload = serializer.deserialize(payloadColumnResolver.extractPayload(rs, "PAYLOAD"), clazz);
        return new ReferentialPayload<>(failoverName, failoverKey, false, asOf, expireOn, payload);
    }
}
