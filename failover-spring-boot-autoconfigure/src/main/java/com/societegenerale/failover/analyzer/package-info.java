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

/**
 * Spring Boot failure analyser for missing failover bean definitions.
 *
 * <p>{@link com.societegenerale.failover.analyzer.FailoverFailureAnalyzer} intercepts
 * {@link org.springframework.beans.factory.NoSuchBeanDefinitionException} at startup
 * and emits a human-readable diagnostic when a required failover bean (key generator,
 * expiry policy, or payload splitter) is not registered.
 */
package com.societegenerale.failover.analyzer;
