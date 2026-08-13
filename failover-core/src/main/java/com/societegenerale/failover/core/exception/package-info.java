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
 * Exception model for method-level failover handling.
 *
 * <p>{@link com.societegenerale.failover.core.exception.MethodExceptionContext} carries the
 * original exception and any recovered result so that a
 * {@link com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy} can decide
 * whether to return stale data or rethrow.
 */
package com.societegenerale.failover.core.exception;
