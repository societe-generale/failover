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

import com.societegenerale.failover.MyTestApplication;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreJdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
@SpringBootTest(classes = {MyTestApplication.class})
@TestPropertySource(properties = {"failover.store.type=jdbc", "failover.store.jdbc.table-prefix=DEAL_"})
class FailoverJdbcStoreAutoConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FailoverStore<Object> failoverStore;

    @Test
    @DisplayName("should load all the basic default beans")
    void shouldLoadAllTheBasicDefaultBeans() {
        assertBasicBean(applicationContext);
    }

    @Test
    @DisplayName("should load jdbc failover store bean")
    void shouldLoadJdbcFailoverStoreBean() throws Exception {
        assertThat(failoverStore).isNotNull();
        Object target = AopUtils.isAopProxy(failoverStore)
                ? ((Advised) failoverStore).getTargetSource().getTarget()
                : failoverStore;
        FailoverStoreAsync<Object> async = cast(target);
        assertThat(async).isNotNull();
        FailoverStore<Object> inner = requireNonNull(async.getFailoverStore());
        assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isInstanceOf(FailoverStoreJdbc.class);
    }

    @Test
    @DisplayName("should wrap async and default stores with the given failover store jdbc")
    @SuppressWarnings("unchecked")
    void shouldWrapAsyncAndDefaultStoresWithTheGivenFailoverStoreJdbc() throws Exception {
        Object target = AopUtils.isAopProxy(failoverStore)
                ? ((Advised) failoverStore).getTargetSource().getTarget()
                : failoverStore;
        assertThat(target).isNotNull();
        assertThat(target).isInstanceOf(FailoverStoreAsync.class);
        FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) target).getFailoverStore());
        assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        FailoverStore<Object> innermost = requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore());
        assertThat(innermost).isInstanceOf(FailoverStoreJdbc.class);
    }
}