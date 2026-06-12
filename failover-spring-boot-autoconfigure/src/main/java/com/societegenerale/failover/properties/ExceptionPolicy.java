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

package com.societegenerale.failover.properties;

/**
 * Strategy that governs how the failover framework handles exceptions thrown by
 * the primary method when recovery is unavailable or the stored entry has expired.
 *
 * @author Anand Manissery
 */
public enum ExceptionPolicy {

    /**
     * For re throw the same exception when unable to recover or expired
     */
    RETHROW,

    /**
     * For always suppress the error and return payload / null in case of failover failure or expired
     */
    NEVER_THROW,

    /**
     * For any other custom implementation, please use custom type
     */
    CUSTOM
}
