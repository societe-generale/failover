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

package com.societegenerale.failover.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link FailoverStore} decorator that offloads write operations ({@link #store},
 * {@link #delete}, {@link #cleanByExpiry}) to a {@link TaskExecutor}, keeping the calling
 * thread unblocked. {@link #find} remains synchronous because the caller needs the result.
 *
 * <h2>Threading contract</h2>
 * <p>Each write method captures only the method arguments in its executor lambda — never any
 * {@code ThreadLocal} values (such as a tenant context or security context). This is intentional:
 *
 * <ul>
 *   <li>Tenant routing (when multitenant) is performed by the <em>outermost</em> wrapper
 *       ({@code MultiTenantFailoverStore}) on the <b>calling thread</b>, before this class is
 *       reached. By the time {@link #store} is invoked, {@code this.failoverStore} is already
 *       scoped to the correct tenant — no re-resolution is needed inside the executor.</li>
 *   <li>{@code ThreadLocal} values are not propagated to executor threads by default in Java.
 *       Relying on them inside the lambda would cause silent, hard-to-debug failures on pooled
 *       threads. The design avoids this by ensuring all context-sensitive routing happens before
 *       the thread boundary.</li>
 * </ul>
 *
 * <p>The {@link TaskExecutor} is injected at construction time so this decorator works
 * correctly whether the instance is a Spring-managed bean or created programmatically inside a
 * factory (e.g. one per tenant in multitenant mode) — unlike Spring's {@code @Async} annotation,
 * which silently degrades to synchronous execution for non-Spring-managed instances.
 *
 * @param <T> the type of the payload
 * @author Anand Manissery
 */
@Slf4j
@RequiredArgsConstructor
public class FailoverStoreAsync<T> implements FailoverStore<T> {

    @Getter
    private final FailoverStore<T> failoverStore;

    private final TaskExecutor executor;

    /**
     * Submits the store operation to the executor.
     *
     * <p>{@code referentialPayload} is captured by reference in the lambda — safe because
     * {@link ReferentialPayload} is treated as immutable after being passed here.
     * No {@code ThreadLocal} values are read inside the lambda.
     */
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        executor.execute(() -> {
            try {
                log.info("Failover Store : Async call for storing information on '{}' for failover. ReferentialPayload : {{}}",
                        referentialPayload.getName(), referentialPayload);
                failoverStore.store(referentialPayload);
            } catch (Exception e) {
                log.error("Failover Store : Async store failed for '{}'. Failover data not persisted. Cause: {}",
                        referentialPayload.getName(), e.getMessage(), e);
            }
        });
    }

    /**
     * Submits the delete operation to the executor.
     * No {@code ThreadLocal} values are read inside the lambda.
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        executor.execute(() -> {
            try {
                log.info("Failover Store : Async call for deleting the expired payload on '{}' from failover store. ReferentialPayload : {{}}",
                        referentialPayload.getName(), referentialPayload);
                failoverStore.delete(referentialPayload);
            } catch (Exception e) {
                log.error("Failover Store : Async delete failed for '{}'. Cause: {}",
                        referentialPayload.getName(), e.getMessage(), e);
            }
        });
    }

    /**
     * Executes find synchronously on the calling thread — the result is needed immediately.
     * No thread boundary is crossed.
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return failoverStore.find(name, key);
    }

    /**
     * Submits the cleanup operation to the executor.
     * No {@code ThreadLocal} values are read inside the lambda.
     */
    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        executor.execute(() -> {
            try {
                log.info("Failover Store : Async call for clean the expired payload by the given expiry '{}'", expiry);
                failoverStore.cleanByExpiry(expiry);
            } catch (Exception e) {
                log.error("Failover Store : Async cleanByExpiry failed. Cause: {}", e.getMessage(), e);
            }
        });
    }
}