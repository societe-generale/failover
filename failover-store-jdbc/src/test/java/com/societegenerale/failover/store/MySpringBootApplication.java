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

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.resolver.*;
import com.societegenerale.failover.store.serializer.JsonSerializer;
import com.societegenerale.failover.store.serializer.Serializer;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Anand Manissery
 */
@SpringBootApplication
public class MySpringBootApplication {

    static void main(String[] args) {
        SpringApplication.run(MySpringBootApplication.class, args);
    }

    @Bean
    public DatabaseResolver databaseResolver(JdbcTemplate jdbcTemplate) {
        return new DefaultDatabaseResolver(jdbcTemplate);
    }

    @Bean
    public FailoverStoreQueryResolver failoverStoreQueryResolver(Serializer serializer, DatabaseResolver databaseResolver) {
        return new DefaultFailoverStoreQueryResolver("TEST_", serializer, databaseResolver, new VarcharPayloadColumnResolver());
    }

    @Bean
    public <T> FailoverStoreJdbc<T> failoverStoreJdbc(JdbcTemplate jdbcTemplate, FailoverStoreQueryResolver failoverStoreQueryResolver, RowMapper<ReferentialPayload<T>>  rowMapper) {
        return new FailoverStoreJdbc<>(jdbcTemplate, failoverStoreQueryResolver, rowMapper);
    }

    @Bean
    public PayloadColumnResolver payloadColumnResolver() {
        return new VarcharPayloadColumnResolver();
    }

    @Bean
    public <T> RowMapper<ReferentialPayload<T>>  rowMapper(PayloadColumnResolver payloadColumnResolver, Serializer serializer) {
        return new ReferentialPayloadRowMapper<>(payloadColumnResolver, serializer);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new JsonMapper();
    }

    @Bean
    public Serializer serializer(ObjectMapper objectMapper) {
        return new JsonSerializer(objectMapper);
    }
}
