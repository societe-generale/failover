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
 * Caffeine-backed in-process {@link com.societegenerale.failover.core.store.FailoverStore}.
 *
 * <p>{@link com.societegenerale.failover.store.caffeine.FailoverStoreCaffeine} holds all entries in a
 * single Caffeine {@code Cache} keyed by {@code "<name>##<key>"}, deriving each entry's TTL from its
 * own {@code expireOn} via a per-entry {@code Expiry} policy. Optionally size-bounded via
 * {@code failover.store.caffeine.max-size} (Window TinyLFU eviction). Suitable for single-node,
 * non-persistent caching.
 */
package com.societegenerale.failover.store.caffeine;
