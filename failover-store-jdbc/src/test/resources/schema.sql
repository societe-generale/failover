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

DROP TABLE IF EXISTS TEST_FAILOVER_STORE;
CREATE TABLE TEST_FAILOVER_STORE (
    FAILOVER_NAME VARCHAR(50) NOT NULL,
    FAILOVER_KEY VARCHAR(256) NOT NULL,
    AS_OF TIMESTAMP(9) NOT NULL,
    EXPIRE_ON TIMESTAMP(9) NOT NULL,
    PAYLOAD VARCHAR(1000),
    PAYLOAD_CLASS VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);