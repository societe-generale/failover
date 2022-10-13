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

package com.societegenerale.failover.analyzer;

import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverType;
import com.societegenerale.failover.properties.StoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverFailureAnalyzerTest {

    private final Throwable rootFailure = new RuntimeException("Some exception");

    @Mock
    private Environment environment;

    private NoSuchBeanDefinitionException cause;

    private FailoverFailureAnalyzer failoverFailureAnalyzer;

    @BeforeEach
    void setUp() {
        this.failoverFailureAnalyzer = new FailoverFailureAnalyzer();
        this.failoverFailureAnalyzer.setEnvironment(environment);
    }

    @DisplayName("should handle exception on FailoverStore with CAFFEINE")
    @Test
    void shouldHandleExceptionWhenFailoverStoreCaffeineConfigured() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(FailoverStore.class));
        given(environment.getProperty("failover.store.type", StoreType.class, StoreType.CUSTOM)).willReturn(StoreType.CAFFEINE);
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some exception description");
        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo("For FailoverStore 'CAFFEINE', you must include 'com.github.ben-manes.caffeine:caffeine' dependency.");
    }

    @DisplayName("should handle exception on FailoverStore with JDBC")
    @Test
    void shouldHandleExceptionWhenFailoverStoreJdbcConfigured() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(FailoverStore.class));
        given(environment.getProperty("failover.store.type", StoreType.class, StoreType.CUSTOM)).willReturn(StoreType.JDBC);
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some exception description");
        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo("For FailoverStore 'JDBC', you must provide 'JdbcTemplate' bean.");
    }

    @DisplayName("should handle exception on FailoverStore with CUSTOM")
    @Test
    void shouldHandleExceptionWhenCustomFailoverStoreConfigured() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(FailoverStore.class));
        given(environment.getProperty("failover.store.type", StoreType.class, StoreType.CUSTOM)).willReturn(StoreType.CUSTOM);
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some exception description");
        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo("For FailoverStore 'CUSTOM', " +
                "Either configured to available stores { [INMEMORY, CAFFEINE, JDBC, CUSTOM] } by configuring 'failover.store.type' property " +
                "OR Consider defining a bean of type 'interface com.societegenerale.failover.core.store.FailoverStore' in your configuration by setting 'failover.store.type=custom'."
        );
    }

    @DisplayName("should handle exception on FailoverExecution with RESILIENCE")
    @Test
    void shouldHandleExceptionWhenFailoverExecutionConfigured() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(FailoverExecution.class));
        given(environment.getProperty("failover.type", FailoverType.class, FailoverType.CUSTOM)).willReturn(FailoverType.RESILIENCE);
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some exception description");
        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo("For FailoverExecution 'RESILIENCE', you must include 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j' dependency.");
    }

    @DisplayName("should handle exception on FailoverExecution with CUSTOM")
    @Test
    void shouldHandleExceptionWhenCustomFailoverExecutionConfigured() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(FailoverExecution.class));
        given(environment.getProperty("failover.type", FailoverType.class, FailoverType.CUSTOM)).willReturn(FailoverType.CUSTOM);
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some exception description");
        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo("For FailoverExecution 'CUSTOM', " +
                "Either configured to available type { [BASIC, RESILIENCE, CUSTOM] } by configuring 'failover.type' property " +
                "OR Consider defining a bean of type 'interface com.societegenerale.failover.core.store.FailoverStore' in your configuration by setting 'failover.type=custom'.");
    }

    @DisplayName("should return Failover Failure Analysis when exception is due to Failover configuration")
    @Test
    void shouldProvideFailoverFailureAnalysis() {
        String description = "Parameter 0 of method failoverStoreJdbc in com.societegenerale.failover.configuration.FailoverJdbcStoreAutoConfiguration";
        ResolvableType resolvableType = ResolvableType.forType(JdbcTemplate.class);
        Throwable rootFailure = new RuntimeException("Some exception");
        NoSuchBeanDefinitionException cause = new NoSuchBeanDefinitionException(resolvableType);

        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, description);

        assertThat(result).isNotNull();
        assertThat(result.getDescription()).contains(description);
        assertThat(result.getCause()).isEqualTo(cause);
    }


    @DisplayName("should return null when description does not contain FailoverJdbcStoreAutoConfiguration for JdbcTemplate related error")
    @Test
    void shouldReturnNullWhenDescriptionDoesNotContainsFailoverJdbcStoreAutoConfigurationClass() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(JdbcTemplate.class));
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some description");
        assertThat(result).isNull();
    }

    @DisplayName("should return null when no issues due to missing JdbcTemplate Or FailoverStore Or FailoverExecution beans")
    @Test
    void shouldReturnNullWhenNoJdbcTemplateOrFailoverStoreOrFailoverExecutionFound() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(DataSource.class));
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, "some description");
        assertThat(result).isNull();
    }

    @DisplayName("should return null when description is null for JdbcTemplate exception")
    @Test
    void shouldReturnNullWhenDescriptionIsNull() {
        cause = new NoSuchBeanDefinitionException(ResolvableType.forType(JdbcTemplate.class));
        FailureAnalysis result = failoverFailureAnalyzer.analyze(rootFailure, cause, null);
        assertThat(result).isNull();
    }

    @DisplayName("should have order zero")
    @Test
    void shouldHaveOrderZero() {
        assertThat(failoverFailureAnalyzer.getOrder()).isZero();
    }
}