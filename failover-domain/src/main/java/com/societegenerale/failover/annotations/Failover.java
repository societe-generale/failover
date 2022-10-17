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

import java.lang.annotation.*;
import java.time.temporal.ChronoUnit;

/**
 * Annotation for handling the failover of the invoking a method.
 *
 * On successful method call, the result of invoking a method will be stored for recovery purpose.
 * On failure method call, the result will be recovered if already exist in the store.
 * <p>Each time an advised method is invoked, failover behavior will be applied, (store on success, recover on failure)
 * for the method invoked for the given arguments.
 *
 * A unique name must be provided for the failover.
 *
 * The expiry of the stored data is managed based on the expiry duration and expiry unit given in the annotation.
 * The default value of expiry duration is 1 and the default value of expiry unit is HOURS which is of type
 * 'java.time.temporal.ChronoUnit'.
 *
 * A sensible default simply uses the method parameters to compute the key or a custom
 * 'com.societegenerale.failover.core.key.KeyGenerator' implementation can
 * replace the default one (see {@link #keyGenerator}) by configuring the custom key generator bean name.
 *
 * <p>If no value is found in the failover store for the computed key, a null value will be returned and
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
     * The name of the failover.
     * <p>A unique name must be provided
     * @return name of the failover
     */
    String name();

    /**
     * The expiry duration for computing the expiry of the entities stored in the failover store.
     * <p>The default value is 1
     * @return expiry duration of the failover
     */
    long expiryDuration() default 1;

    /**
     * The expiry unit @see java.time.temporal.ChronoUnit for computing the expiry of the entities stored in the failover store.
     * <p>The default value is HOURS
     * @return expiry unit of the failover
     */
    ChronoUnit expiryUnit() default ChronoUnit.HOURS;

    /**
     * The bean name of the custom 'com.societegenerale.failover.core.key.KeyGenerator' to use.
     * <p>If not configured, default key generator will be used
     * @return key generator name
     */
    String keyGenerator() default "";

    /**
     * The bean name of the custom 'com.societegenerale.failover.core.expiry.ExpiryPolicy' to use.
     * <p>If not configured, default expiry policy will be used
     * @return expiry policy name
     */
    String expiryPolicy() default "";
}
