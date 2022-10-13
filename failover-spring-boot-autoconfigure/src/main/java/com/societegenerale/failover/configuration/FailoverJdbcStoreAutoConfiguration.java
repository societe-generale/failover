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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreJdbc;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Anand Manissery
 */
@ConditionalOnExpression("${failover.enabled:true} eq true and '${failover.store.type:inmemory}'.toLowerCase() eq 'jdbc'")
@ConditionalOnClass(name = { "javax.sql.DataSource" } )
@EnableConfigurationProperties(FailoverProperties.class)
@Configuration
@AllArgsConstructor
@Slf4j
public class FailoverJdbcStoreAutoConfiguration {

    protected final FailoverProperties failoverProperties;

    @Bean
    public FailoverStore<Object> failoverStoreJdbc(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        log.info("FailoverStore configured to FailoverStoreJdbc.");
        return new FailoverStoreAsync<>(new FailoverStoreJdbc<>(failoverProperties.getStore().getJdbc().getTablePrefix(), jdbcTemplate, objectMapper));
    }
}
