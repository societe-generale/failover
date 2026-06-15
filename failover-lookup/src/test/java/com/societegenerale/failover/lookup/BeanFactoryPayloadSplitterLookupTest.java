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

package com.societegenerale.failover.lookup;

import com.societegenerale.failover.core.payload.splitter.PayloadSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class BeanFactoryPayloadSplitterLookupTest {

    @Mock
    private PayloadSplitter<Object, Object> payloadSplitter;

    @Mock
    private BeanFactory beanFactory;

    private BeanFactoryPayloadSplitterLookup<Object, Object> lookup;

    @BeforeEach
    void setUp() {
        lookup = new BeanFactoryPayloadSplitterLookup<>();
    }

    @Test
    @DisplayName("should return the PayloadSplitter bean by name")
    void shouldReturnThePayloadSplitterBeanByName() {
        given(beanFactory.getBean("my-splitter", PayloadSplitter.class)).willReturn(payloadSplitter);
        lookup.setBeanFactory(beanFactory);

        PayloadSplitter<?, ?> result = lookup.lookup("my-splitter");

        assertThat(result).isEqualTo(payloadSplitter);
        verify(beanFactory).getBean("my-splitter", PayloadSplitter.class);
    }

    @Test
    @DisplayName("should delegate each lookup call to BeanFactory")
    void shouldDelegateEachLookupCallToBeanFactory() {
        given(beanFactory.getBean("splitter-a", PayloadSplitter.class)).willReturn(payloadSplitter);
        lookup.setBeanFactory(beanFactory);

        lookup.lookup("splitter-a");
        lookup.lookup("splitter-a");

        verify(beanFactory, Mockito.times(2)).getBean("splitter-a", PayloadSplitter.class);
    }
}
