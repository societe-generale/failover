/*
 * Copyright 2022-2026, Société Générale All rights reserved.
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

package com.societegenerale.failover.store.dialect;

import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the {@code INSERT ... ON CONFLICT DO UPDATE} PostgreSQL merge dialect against a real
 * PostgreSQL container.
 *
 * @author Anand Manissery
 */
@DisplayName("FailoverStoreJdbc — PostgreSQL dialect")
class PostgresDialectIT extends AbstractDialectIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Override
    protected JdbcDatabaseContainer<?> container() {
        return POSTGRES;
    }

    @Override
    protected String ddl() {
        return """
                CREATE TABLE FAILOVER_STORE (
                    FAILOVER_NAME  VARCHAR(50)  NOT NULL,
                    FAILOVER_KEY   VARCHAR(256) NOT NULL,
                    AS_OF          TIMESTAMP    NOT NULL,
                    EXPIRE_ON      TIMESTAMP    NOT NULL,
                    PAYLOAD        VARCHAR(2000),
                    PAYLOAD_CLASS  VARCHAR(256),
                    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
                )""";
    }

    @Override
    protected String expectedMergeFragment() {
        return "ON CONFLICT";
    }
}
