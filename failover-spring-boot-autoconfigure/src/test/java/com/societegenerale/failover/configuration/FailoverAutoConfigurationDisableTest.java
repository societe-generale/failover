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
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.report.ReportPublisher;
import com.societegenerale.failover.core.store.FailoverStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBeansAreEmpty;

/**
 * @author Anand Manissery
 */
@SpringBootTest(classes = {MyTestApplication.class})
@TestPropertySource(properties = {"failover.enabled=false"})
class FailoverAutoConfigurationDisableTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("should not load any failover beans")
    void shouldLoadAllTheBasicDefaultBeans() {
        assertBeansAreEmpty(applicationContext,
            KeyGenerator.class, FailoverClock.class, FailoverStore.class,
            ExpiryPolicy.class, PayloadEnricher.class, RecoveredPayloadHandler.class,
            FailoverHandler.class, FailoverExecution.class, ReportPublisher.class
        );
    }

}