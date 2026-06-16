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

package com.societegenerale.failover.scanner;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.scanner.FailoverScannerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Anand Manissery
 */
class SpringContextFailoverScannerTest {

    private SpringContextFailoverScanner scanner;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        scanner = new SpringContextFailoverScanner();
        scanner.setApplicationContext(applicationContext);
    }

    @Test
    @DisplayName("should discover @Failover on concrete class methods")
    void shouldDiscoverFailoverOnConcreteMethods() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"referentialBean"});
        doReturn(ConcreteReferential.class).when(applicationContext).getType("referentialBean");

        scanner.afterSingletonsInstantiated();

        List<Failover> all = scanner.findAllFailover();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Failover::name).containsExactlyInAnyOrder("find-by-id", "find-all");
    }

    @Test
    @DisplayName("should discover @Failover placed on interface methods (Spring annotation bridge)")
    void shouldDiscoverFailoverOnInterfaceMethods() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"impl"});
        doReturn(ReferentialImpl.class).when(applicationContext).getType("impl");

        scanner.afterSingletonsInstantiated();

        List<Failover> all = scanner.findAllFailover();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().name()).isEqualTo("interface-method");
    }

    @Test
    @DisplayName("should return null when name not found")
    void shouldReturnNullForMissingName() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        scanner.afterSingletonsInstantiated();
        assertThat(scanner.findFailoverByName("missing")).isNull();
    }

    @Test
    @DisplayName("should return failover by name")
    void shouldReturnFailoverByName() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"bean"});
        doReturn(ConcreteReferential.class).when(applicationContext).getType("bean");

        scanner.afterSingletonsInstantiated();

        Failover result = scanner.findFailoverByName("find-by-id");
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("find-by-id");
        assertThat(result.expiryDuration()).isEqualTo(1);
        assertThat(result.expiryUnit()).isEqualTo(ChronoUnit.HOURS);
    }

    @Test
    @DisplayName("should throw FailoverScannerException on duplicate @Failover names")
    void shouldThrowOnDuplicateNames() {
        when(applicationContext.getBeanDefinitionNames())
            .thenReturn(new String[]{"bean1", "bean2"});
        doReturn(DuplicateA.class).when(applicationContext).getType("bean1");
        doReturn(DuplicateB.class).when(applicationContext).getType("bean2");

        assertThatThrownBy(() -> scanner.afterSingletonsInstantiated())
            .isInstanceOf(FailoverScannerException.class)
            .hasMessageContaining("Duplicate @Failover name")
            .hasMessageContaining("duplicate-name");
    }

    @Test
    @DisplayName("should return empty list when no @Failover found")
    void shouldReturnEmptyWhenNoFailovers() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"plainBean"});
        doReturn(PlainBean.class).when(applicationContext).getType("plainBean");

        scanner.afterSingletonsInstantiated();

        assertThat(scanner.findAllFailover()).isEmpty();
    }

    @Test
    @DisplayName("should skip beans whose type cannot be determined")
    void shouldSkipBeansWithUndeterminableType() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"problem", "good"});
        when(applicationContext.getType("problem")).thenThrow(new RuntimeException("type error"));
        doReturn(ConcreteReferential.class).when(applicationContext).getType("good");

        scanner.afterSingletonsInstantiated();

        assertThat(scanner.findAllFailover()).hasSize(2);
    }

    @Test
    @DisplayName("findAllFailover returns empty list before scan runs")
    void shouldReturnEmptyBeforeScan() {
        assertThat(scanner.findAllFailover()).isEmpty();
    }

    // ── findAllPayloadTypes ─────────────────────────────────────────────────────

    @Test
    @DisplayName("payload types include the return type and the element type of a collection return")
    void shouldCollectReturnAndCollectionElementTypes() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"referentialBean"});
        doReturn(ConcreteReferential.class).when(applicationContext).getType("referentialBean");

        scanner.afterSingletonsInstantiated();

        // findById → String ; findAll → element of List<String> = String
        assertThat(scanner.findAllPayloadTypes()).containsExactly(String.class);
    }

    @Test
    @DisplayName("payload types resolve POJO returns and array component types; void is ignored")
    void shouldCollectPojoAndArrayComponentTypesAndIgnoreVoid() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"varied"});
        doReturn(VariedReturns.class).when(applicationContext).getType("varied");

        scanner.afterSingletonsInstantiated();

        assertThat(scanner.findAllPayloadTypes()).containsExactlyInAnyOrder(SamplePojo.class);
    }

    @Test
    @DisplayName("findAllPayloadTypes returns empty set before scan runs")
    void payloadTypesEmptyBeforeScan() {
        assertThat(scanner.findAllPayloadTypes()).isEmpty();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static class ConcreteReferential {
        @Failover(name = "find-by-id")
        public String findById(Long id) { return null; }

        @Failover(name = "find-all")
        public List<String> findAll() { return null; }
    }

    static class SamplePojo { }

    static class VariedReturns {
        @Failover(name = "pojo")
        public SamplePojo pojo() { return null; }

        @Failover(name = "array")
        public SamplePojo[] array() { return null; }

        @Failover(name = "voidish")
        public void doNothing() { /*for test only*/ }
    }

    interface ReferentialInterface {
        @Failover(name = "interface-method")
        String findByCode(String code);
    }

    static class ReferentialImpl implements ReferentialInterface {
        @Override
        public String findByCode(String code) { return null; }
    }

    static class DuplicateA {
        @Failover(name = "duplicate-name")
        public String methodA() { return null; }
    }

    static class DuplicateB {
        @Failover(name = "duplicate-name")
        public String methodB() { return null; }
    }

    static class PlainBean {
        public String noAnnotation() { return null; }
    }
}
