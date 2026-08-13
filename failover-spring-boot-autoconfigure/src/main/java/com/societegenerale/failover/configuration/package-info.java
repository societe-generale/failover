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
 * Spring Boot auto-configuration for the failover framework.
 *
 * <p>Registers the aspect, store, scheduler, scatter/gather executor, and
 * reporter beans.  Select the store type via {@code failover.store.type}
 * ({@code INMEMORY}, {@code CAFFEINE}, {@code JDBC}, or {@code CUSTOM}).
 */
package com.societegenerale.failover.configuration;
