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
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreCaffeine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
@SpringBootTest(classes = {MyTestApplication.class})
@TestPropertySource(properties = {"failover.store.type=caffeine"})
class FailoverCaffeineStoreAutoConfigurationTest {

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
    @DisplayName("should load caffeine failover store bean")
    void shouldLoadCaffeineFailoverStoreBean() throws Exception {
        assertThat(failoverStore).isNotNull();
        if(AopUtils.isAopProxy(failoverStore) && failoverStore instanceof Advised) {
            Object target = ((Advised)failoverStore).getTargetSource().getTarget();
            assertThat(((FailoverStoreAsync)target).getFailoverStore()).isInstanceOf(FailoverStoreCaffeine.class);
        }
    }
}