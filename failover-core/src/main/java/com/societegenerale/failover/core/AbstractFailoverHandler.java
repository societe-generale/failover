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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Base class for {@link FailoverHandler} implementations that do not need the intercepted
 * {@link Method}. It implements the public, method-aware {@link FailoverHandler} contract as
 * {@code final} bridges that drop the {@code method} and delegate to the clean, method-less
 * {@code protected} operations — so subclasses implement only those.
 *
 * <p>The outermost decorator that <em>does</em> need the method (to tag per-method metrics)
 * implements {@link FailoverHandler} directly instead of extending this class.
 *
 * @param <T> the type of the payload managed by this handler
 * @author Anand Manissery
 */
public abstract class AbstractFailoverHandler<T> implements FailoverHandler<T> {

    @Override
    public final T store(@NonNull Failover failover, @NonNull Method method, List<Object> args, T payload) {
        return store(failover, args, payload);
    }

    @Override
    public final T recover(@NonNull Failover failover, @NonNull Method method, List<Object> args, Class<T> clazz, Throwable throwable) {
        return recover(failover, args, clazz, throwable);
    }

    @Override
    public final List<T> recoverAll(@NonNull Failover failover, @NonNull Method method, List<Object> args, Class<T> clazz, Throwable throwable) {
        return recoverAll(failover, args, clazz, throwable);
    }

    /**
     * Stores the payload for later recovery.
     *
     * @param failover annotation metadata for the failover point
     * @param args     method arguments used to derive the store key
     * @param payload  the result to store
     * @return the stored payload
     */
    protected abstract T store(@NonNull Failover failover, List<Object> args, T payload);

    /**
     * Recovers a previously stored payload after a failure.
     *
     * @param failover  annotation metadata for the failover point
     * @param args      method arguments used to derive the lookup key
     * @param clazz     expected return type
     * @param throwable the exception that triggered recovery
     * @return the recovered payload, or {@code null} if not found or expired
     */
    protected abstract T recover(@NonNull Failover failover, List<Object> args, Class<T> clazz, Throwable throwable);

    /**
     * Recovers every stored entry for the failover's referential. Unsupported by default.
     *
     * @param failover  annotation metadata for the failover point
     * @param args      method arguments used to derive the lookup key
     * @param clazz     expected return type
     * @param throwable the exception that triggered recovery
     * @return the recovered payloads
     */
    protected List<T> recoverAll(@NonNull Failover failover, List<Object> args, Class<T> clazz, Throwable throwable) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
