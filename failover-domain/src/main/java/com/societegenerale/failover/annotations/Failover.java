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

package com.societegenerale.failover.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Documented;
import java.time.temporal.ChronoUnit;

/**
 * Annotation for handling the failover of the invoking a method.
 * <p>
 * On successful method call, the result of invoking a method will be stored for recovery purpose.
 * On failure method call, the result will be recovered if already exist in the store.
 * <p>Each time an advised method is invoked, failover behavior will be applied, (store on success, recover on failure)
 * for the method invoked for the given arguments.
 * <p>
 * A unique name must be provided for the failover.
 * <p>
 * The expiry of the stored data is managed based on the expiry duration and expiry unit given in the annotation.
 * The default value of expiry duration is 1 and the default value of expiry unit is HOURS which is of type
 * 'java.time.temporal.ChronoUnit'.
 * <p>
 * A sensible default simply uses the method parameters to compute the key or a custom
 * 'com.societegenerale.failover.core.key.KeyGenerator' implementation can
 * replace the default one (see {@link #keyGenerator}) by configuring the custom key generator bean name.
 * <p>
 * If no value is found in the failover store for the computed key, a null value will be returned and
 * which can be handled by providing a 'com.societegenerale.failover.core.payload.RecoveredPayloadHandler'.
 *
 * @author Anand Manissery
 * @since 1.0.0
 */

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Documented
public @interface Failover {

    /**
     * The name of the failover. A unique name must be provided.
     *
     * @return name of the failover
     */
    String name();

    /**
     * The expiry duration for computing the expiry of the entities stored in the failover store.
     * The default value is 1.
     *
     * @return expiry duration of the failover
     */
    long expiryDuration() default 1;

    /**
     * The expiry duration expression for computing the expiry of the entities stored in the failover store.
     * If you specify expression it will be taken for computing expiry otherwise expiryDuration will be taken.
     * The default value is {@code ""}.
     *
     * @return expiry duration expression of the failover
     */
    String expiryDurationExpression() default "";

    /**
     * The expiry unit for computing the expiry of the entities stored in the failover store.
     * See {@link java.time.temporal.ChronoUnit}. The default value is {@link ChronoUnit#HOURS}.
     *
     * @return expiry unit of the failover
     */
    ChronoUnit expiryUnit() default ChronoUnit.HOURS;

    /**
     * The expiry unit expression for computing the expiry of the entities stored in the failover store.
     * If you specify expression it will be taken for computing expiry otherwise expiryUnit will be taken.
     * The default value is {@code ""}.
     *
     * @return expiry unit expression of the failover
     */
    String expiryUnitExpression() default "";

    /**
     * The bean name of the custom {@code KeyGenerator} to use.
     * If not configured, the default key generator will be used.
     *
     * @return key generator bean name
     */
    String keyGenerator() default "";

    /**
     * The bean name of the custom {@code ExpiryPolicy} to use.
     * If not configured, the default expiry policy will be used.
     *
     * @return expiry policy bean name
     */
    String expiryPolicy() default "";

    /**
     * The bean name of the custom {@code PayloadSplitter} to use for scatter/gather mode.
     *
     * <p>When set, enables scatter/gather mode:
     * <ul>
     *   <li>Store path: the composite method result is split into individual per-entity slices via
     *       {@code PayloadSplitter#split}, each stored under its own UUID key.</li>
     *   <li>Recover path: each individual key is looked up independently; available slices are
     *       merged via {@code PayloadSplitter#merge}. Partial recovery is handled gracefully.</li>
     * </ul>
     *
     * <p>If empty (default), standard single-key behaviour applies.
     *
     * @return payload splitter bean name
     */
    String payloadSplitter() default "";

    /**
     * Optional logical domain for store partitioning.
     *
     * <p>When set, this value is used as the store namespace instead of {@link #name()},
     * and as the prefix for key generation. Two {@code @Failover} annotations with the
     * same {@code domain} share the same store entries — a successful call on either
     * populates data the other can recover from.
     *
     * <p>Typical use: a single-entity endpoint and a scatter/gather list endpoint covering
     * the same business entity type (e.g. {@code domain = "country"}).
     *
     * <p><strong>Expiry consistency:</strong> failovers sharing a domain must use the same
     * expiry configuration. When expiry differs, the last writer overwrites the stored expiry
     * timestamp for that entry. The scanner warns at startup when mismatched expiry is detected
     * within a domain.
     *
     * <p>When empty (default), {@link #name()} is used — existing behavior is preserved.
     *
     * @return domain name, or empty string to default to {@link #name()}
     * @see #name()
     */
    String domain() default "";


    /**
     *
     * @return true when we want to enforce recover all even teh args are not null or empty ( non id args )
     * false (by default) to perform recover all by normal mode with payload splitter.
     * You must configure a proper payload splitter in both the case
     * @see #payloadSplitter()
     */
    boolean recoverAll() default false;


}
