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
 * Spring JDBC {@link org.springframework.jdbc.core.RowMapper} for the failover store table.
 *
 * <p>{@link com.societegenerale.failover.store.jdbc.mapper.ReferentialPayloadRowMapper} converts
 * a {@code FAILOVER_STORE} row into a
 * {@link com.societegenerale.failover.core.payload.ReferentialPayload}, delegating payload
 * column extraction to the configured
 * {@link com.societegenerale.failover.store.jdbc.resolver.PayloadColumnResolver}.
 */
package com.societegenerale.failover.store.jdbc.mapper;
