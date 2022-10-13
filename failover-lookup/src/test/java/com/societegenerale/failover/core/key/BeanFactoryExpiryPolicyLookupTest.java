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

package com.societegenerale.failover.core.key;

import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class BeanFactoryExpiryPolicyLookupTest {

    @Mock
    private ExpiryPolicy<String> expiryPolicy;

    @Mock
    private BeanFactory beanFactory;

    private BeanFactoryExpiryPolicyLookup<String> beanFactoryExpiryPolicyLookup;

    @BeforeEach
    void setUp() {
        beanFactoryExpiryPolicyLookup = new BeanFactoryExpiryPolicyLookup<>();
    }

    @Test
    void shouldReturnTheKeyGeneratorBean() {
        given(beanFactory.getBean("custom-expiry-policy", ExpiryPolicy.class)).willReturn(expiryPolicy);
        beanFactoryExpiryPolicyLookup.setBeanFactory(beanFactory);
        ExpiryPolicy<String> result = beanFactoryExpiryPolicyLookup.lookup("custom-expiry-policy");
        assertThat(result).isEqualTo(expiryPolicy);
        verify(beanFactory).getBean("custom-expiry-policy", ExpiryPolicy.class);
    }
}