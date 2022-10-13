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

import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.StoreType;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreCaffeine;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Anand Manissery
 */
@ConditionalOnExpression("${failover.enabled:true} eq true and '${failover.store.type:inmemory}'.toLowerCase() eq 'caffeine'")
@ConditionalOnClass(name = { "com.github.benmanes.caffeine.cache.Caffeine" })
@Configuration
@AllArgsConstructor
@Slf4j
public class FailoverCaffeineStoreAutoConfiguration {

    @Bean
    public FailoverStore<Object> failoverStore(FailoverScanner failoverScanner) {
        log.warn("FailoverStore configured to FailoverStoreCaffeine. This will be based on caffeine cache and hence you will have some impact on heap for high volume failover storage. Available options are : {{}}", (Object) StoreType.values());
        return new FailoverStoreAsync<>(new FailoverStoreCaffeine<>(failoverScanner));
    }
}
