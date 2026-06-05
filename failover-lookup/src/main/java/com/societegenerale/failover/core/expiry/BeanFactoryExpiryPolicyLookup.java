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

package com.societegenerale.failover.core.expiry;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;

import static com.societegenerale.failover.core.util.CastingUtils.cast;

/**
 * Spring {@link BeanFactory}-backed implementation of {@link ExpiryPolicyLookup}.
 * Resolves an {@link ExpiryPolicy} by delegating to {@link BeanFactory#getBean(String, Class)},
 * which matches by both qualifier and bean name.
 *
 * @param <T> the payload type the resolved policy operates on
 * @author Anand Manissery
 */
public class BeanFactoryExpiryPolicyLookup<T> implements ExpiryPolicyLookup<T>, BeanFactoryAware {

    private BeanFactory beanFactory;

    /**
     * Returns the {@link ExpiryPolicy} bean registered under {@code name}.
     *
     * @param name qualifier or bean name as declared in {@code @Failover(expiryPolicy = "...")}
     * @return matching {@link ExpiryPolicy}
     */
    @Override
    public ExpiryPolicy<T> lookup(String name) {
        return cast(beanFactory.getBean(name, ExpiryPolicy.class));
    }

    /**
     * Injects the Spring {@link BeanFactory} used for expiry-policy lookups.
     *
     * @param beanFactory the bean factory to use
     * @throws BeansException if setting the bean factory fails
     */
    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }
}
