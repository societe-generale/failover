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

package com.societegenerale.failover.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverClusterPublisherPropertiesTest {

    @Test
    void defaultsHaveJdbcDisabled() {
        FailoverClusterPublisherProperties props = new FailoverClusterPublisherProperties();
        assertThat(props.jdbc().enabled()).isFalse();
        assertThat(props.jdbc().tablePrefix()).isEmpty();
        assertThat(props.publishUrl()).isEmpty();
    }

    @Test
    void jdbcEnabledAloneIsValid() {
        FailoverClusterPublisherProperties props = new FailoverClusterPublisherProperties(
                "", 15, 300, "", "", "", false, new FailoverClusterPublisherProperties.Heartbeat(),
                new FailoverClusterPublisherProperties.Jdbc(true, "DEMO_"));

        assertThat(props.jdbc().enabled()).isTrue();
        assertThat(props.jdbc().tablePrefix()).isEqualTo("DEMO_");
    }

    @Test
    void publishUrlAloneIsValid() {
        FailoverClusterPublisherProperties props = new FailoverClusterPublisherProperties(
                "http://dashboard:8080/failover-dashboard", 15, 300, "", "", "", false,
                new FailoverClusterPublisherProperties.Heartbeat(), new FailoverClusterPublisherProperties.Jdbc());

        assertThat(props.publishUrl()).isNotBlank();
        assertThat(props.jdbc().enabled()).isFalse();
    }

    @Test
    void jdbcEnabledAndPublishUrlTogetherRejected() {
        assertThatThrownBy(() -> new FailoverClusterPublisherProperties(
                "http://dashboard:8080/failover-dashboard", 15, 300, "", "", "", false,
                new FailoverClusterPublisherProperties.Heartbeat(),
                new FailoverClusterPublisherProperties.Jdbc(true, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }
}
