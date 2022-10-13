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

package com.societegenerale.failover.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;

import static java.lang.Class.forName;
import static org.springframework.util.StringUtils.replace;

/**
 * @author Anand Manissery
 */
@Slf4j
public class FailoverStoreJdbc<T> implements FailoverStore<T> {

    private static final String PREFIX = "%PREFIX%";

    private static final String INSERT_QUERY = "INSERT into " + PREFIX + "FAILOVER_STORE ( AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS, FAILOVER_NAME, FAILOVER_KEY ) VALUES ( ?, ?, ?, ?, ?, ? )";

    private static final String UPDATE_QUERY = "UPDATE " + PREFIX + "FAILOVER_STORE SET AS_OF = ? , EXPIRE_ON = ? , PAYLOAD = ? , PAYLOAD_CLASS = ? WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";

    private static final String SELECT_QUERY = "SELECT FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS from " + PREFIX + "FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";

    private static final String DELETE_QUERY = "DELETE FROM " + PREFIX + "FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";

    private static final String CLEAN_UP_QUERY = "DELETE FROM " + PREFIX + "FAILOVER_STORE WHERE EXPIRE_ON < ?";

    private final String tablePrefix;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final String insertQuery;

    private final String updateQuery;

    private final String selectQuery;

    private final String deleteQuery;

    private final String cleanUpQuery;

    public FailoverStoreJdbc(String tablePrefix, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.tablePrefix = tablePrefix;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.insertQuery = getQuery(INSERT_QUERY);
        this.updateQuery = getQuery(UPDATE_QUERY);
        this.selectQuery = getQuery(SELECT_QUERY);
        this.deleteQuery = getQuery(DELETE_QUERY);
        this.cleanUpQuery = getQuery(CLEAN_UP_QUERY);
    }

    @SneakyThrows
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {

        Optional<ReferentialPayload<T>> optionalReferentialPayloadFromDB = find(referentialPayload.getName(), referentialPayload.getKey());

        String executeQuery = optionalReferentialPayloadFromDB.isPresent() ? updateQuery : insertQuery;

        Object[] objects = {
                Timestamp.valueOf(referentialPayload.getAsOf()),
                Timestamp.valueOf(referentialPayload.getExpireOn()),
                referentialPayload.getPayload() == null ? null: objectMapper.writeValueAsString(referentialPayload.getPayload()),
                referentialPayload.getPayload() == null ? null : referentialPayload.getPayload().getClass().getName(),
                referentialPayload.getName(),
                referentialPayload.getKey()
        };
        int[] types = new int[] {
                Types.TIMESTAMP,
                Types.TIMESTAMP,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
        };
        int count = jdbcTemplate.update(executeQuery, objects, types);
        log.debug("Referential payload inserted/updated. No of record inserted : '{}'", count);
    }

    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        int count = jdbcTemplate.update(deleteQuery, new Object[]{referentialPayload.getName(), referentialPayload.getKey()}, new int[]{Types.VARCHAR, Types.VARCHAR});
        log.debug("Referential payload deleted. No of record deleted : '{}'", count);
    }

    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(selectQuery, new Object[]{name, key}, new int[]{Types.VARCHAR, Types.VARCHAR}, (resultSet, index) -> {
                String failoverName = resultSet.getString("FAILOVER_NAME");
                String failoverKey = resultSet.getString("FAILOVER_KEY");
                LocalDateTime asOf = resultSet.getTimestamp("AS_OF").toLocalDateTime();
                LocalDateTime expireOn = resultSet.getTimestamp("EXPIRE_ON").toLocalDateTime();
                String payloadClass = resultSet.getString("PAYLOAD_CLASS");
                T payload = getPayload(resultSet.getString("PAYLOAD"), payloadClass);
                return new ReferentialPayload<>(failoverName, failoverKey, false, asOf, expireOn, payload);
            }));
        }
        catch (EmptyResultDataAccessException e) {
            log.debug("No referential found for name : '{}'", name, e);
            return Optional.empty();
        }
    }

    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        int count = jdbcTemplate.update(cleanUpQuery, new Object[]{expiry}, new int[]{Types.TIMESTAMP});
        log.debug("Referential payload cleaned up by given expiry : '{}' . No of record deleted : '{}'", expiry, count);
    }

    private String getQuery(String query) {
        return replace(query, PREFIX, this.tablePrefix);
    }

    @SneakyThrows
    private T getPayload(String payload, String clazzString) {
        if(payload==null) {
            return null;
        }
        Class<T> clazz = (Class<T>) forName(clazzString);
        return objectMapper.readValue(payload, clazz);
    }
}
