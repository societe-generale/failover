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

import com.societegenerale.failover.core.BasicFailoverExecution;
import com.societegenerale.failover.execution.resilience.ResilienceFailoverExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import static org.assertj.core.api.Assertions.assertThat;

/// @author Anand Manissery
class ResilienceFailoverExecutionAutoConfigurationTest {

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @DisplayName("when default failover type (basic)")
    class WhenDefaultType {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("ResilienceFailoverExecution should NOT be registered")
        void resilienceFailoverExecutionNotRegistered() {
            assertThat(applicationContext.getBeansOfType(ResilienceFailoverExecution.class)).isEmpty();
        }

        @Test
        @DisplayName("BasicFailoverExecution should be registered")
        void basicFailoverExecutionRegistered() {
            assertThat(applicationContext.getBean(BasicFailoverExecution.class)).isNotNull();
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.type=resilience"})
    @DisplayName("when failover type is resilience")
    class WhenResilienceType {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("should load ResilienceFailoverExecution bean")
        void shouldLoadResilienceFailoverExecutionBean() {
            assertThat(applicationContext.getBean(ResilienceFailoverExecution.class)).isNotNull();
        }
    }
}
