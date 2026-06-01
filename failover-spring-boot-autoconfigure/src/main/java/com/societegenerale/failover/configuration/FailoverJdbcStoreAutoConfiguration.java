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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.store.FailoverStoreJdbc;
import com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.resolver.*;
import com.societegenerale.failover.store.serializer.JsonSerializer;
import com.societegenerale.failover.store.serializer.Serializer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot auto-configuration that registers all beans required for the JDBC-backed
 * failover store.
 *
 * <p>Activated when:
 * <ul>
 *   <li>{@code failover.enabled} is {@code true} (default), and</li>
 *   <li>{@code failover.store.type} is {@code jdbc}.</li>
 * </ul>
 *
 * <p>Every bean is conditional on a missing bean of the same type, so applications can
 * override any individual component by declaring their own bean.
 *
 * @author Anand Manissery
 */
@ConditionalOnExpression("${failover.enabled:true} eq true and '${failover.store.type:inmemory}'.toLowerCase() eq 'jdbc'")
@ConditionalOnClass(name = {"javax.sql.DataSource"})
@EnableConfigurationProperties(FailoverProperties.class)
@AllArgsConstructor
@AutoConfiguration
@Slf4j
public class FailoverJdbcStoreAutoConfiguration {

    protected final FailoverProperties failoverProperties;

    /**
     * Registers a {@link com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper}
     * unless a {@code RowMapper<ReferentialPayload<Object>>} bean is already present.
     */
    @Bean
    @ConditionalOnMissingBean
    public RowMapper<ReferentialPayload<Object>> rowMapper(PayloadColumnResolver payloadColumnResolver, Serializer serializer) {
        return new ReferentialPayloadRowMapper<>(payloadColumnResolver, serializer);
    }

    /**
     * Registers a {@link JsonSerializer} backed by the application's {@link ObjectMapper}
     * unless a {@link Serializer} bean is already present.
     */
    @Bean
    @ConditionalOnMissingBean
    public Serializer serializer(ObjectMapper objectMapper) {
        return new JsonSerializer(objectMapper);
    }

    /**
     * Registers a {@link com.societegenerale.failover.store.resolver.VarcharPayloadColumnResolver}
     * (VARCHAR payload column) unless a {@link PayloadColumnResolver} bean is already present.
     */
    @Bean
    @ConditionalOnMissingBean
    public PayloadColumnResolver payloadColumnHandler() {
        return new VarcharPayloadColumnResolver();
    }

    /**
     * Registers a {@link com.societegenerale.failover.store.resolver.DefaultDatabaseResolver}
     * unless a {@link DatabaseResolver} bean is already present.
     */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseResolver databaseResolver(JdbcTemplate jdbcTemplate) {
        return new DefaultDatabaseResolver(jdbcTemplate);
    }

    /**
     * Registers a {@link com.societegenerale.failover.store.resolver.DefaultFailoverStoreQueryResolver}
     * configured with the table prefix from {@link com.societegenerale.failover.properties.FailoverProperties},
     * unless a {@link FailoverStoreQueryResolver} bean is already present.
     */
    @Bean
    @ConditionalOnMissingBean
    public FailoverStoreQueryResolver failoverStoreQueryResolver(Serializer serializer,
                                                                 DatabaseResolver databaseResolver,
                                                                 PayloadColumnResolver payloadColumnResolver) {
        return new DefaultFailoverStoreQueryResolver(failoverProperties.getStore().getJdbc().getTablePrefix(),
                                                     serializer,
                                                     databaseResolver,
                                                     payloadColumnResolver);
    }

    /**
     * Registers a {@link com.societegenerale.failover.store.FailoverStoreJdbc} as the
     * primary {@link FailoverStore} bean unless one is already present.
     */
    @Bean
    @ConditionalOnMissingBean
    public FailoverStore<Object> failoverStoreJdbc(JdbcTemplate jdbcTemplate, FailoverStoreQueryResolver failoverStoreQueryResolver, RowMapper<ReferentialPayload<Object>> rowMapper) {
        log.info("FailoverStore configured to FailoverStoreJdbc.");
        return new FailoverStoreJdbc<>(jdbcTemplate, failoverStoreQueryResolver, rowMapper);
    }
}
