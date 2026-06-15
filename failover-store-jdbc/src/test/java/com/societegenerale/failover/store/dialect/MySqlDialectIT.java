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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the {@code INSERT ... ON DUPLICATE KEY UPDATE} MySQL merge dialect against a real
 * MySQL container.
 *
 * @author Anand Manissery
 */
@DisplayName("FailoverStoreJdbc — MySQL dialect")
class MySqlDialectIT extends AbstractDialectIT {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Override
    protected JdbcDatabaseContainer<?> container() {
        return MYSQL;
    }

    @Override
    protected String ddl() {
        return """
                CREATE TABLE FAILOVER_STORE (
                    FAILOVER_NAME  VARCHAR(50)  NOT NULL,
                    FAILOVER_KEY   VARCHAR(256) NOT NULL,
                    AS_OF          DATETIME(6)  NOT NULL,
                    EXPIRE_ON      DATETIME(6)  NOT NULL,
                    PAYLOAD        VARCHAR(2000),
                    PAYLOAD_CLASS  VARCHAR(256),
                    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
                )""";
    }

    @Override
    protected String expectedMergeFragment() {
        return "ON DUPLICATE KEY UPDATE";
    }
}
