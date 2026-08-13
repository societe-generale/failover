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
 * Central failover handler and execution abstractions.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link com.societegenerale.failover.core.DefaultFailoverHandler} — store + recover pipeline</li>
 *   <li>{@link com.societegenerale.failover.core.AdvancedFailoverHandler} — adds async, enrichment, and policy hooks</li>
 *   <li>{@link com.societegenerale.failover.core.ScatterGatherFailoverHandler} — parallel slice store/recover</li>
 * </ul>
 */
package com.societegenerale.failover.core;
