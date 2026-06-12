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

package com.societegenerale.failover.core.store;

/**
 * Thrown when a {@link FailoverStore} operation (store, find, delete, or clean) fails.
 *
 * @author Anand Manissery
 */
public class FailoverStoreException extends RuntimeException {

    /**
     * Creates an exception with the given detail message.
     *
     * @param message description of the store operation failure
     */
    public FailoverStoreException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given detail message and cause.
     *
     * @param message description of the store operation failure
     * @param cause   the underlying exception
     */
    public FailoverStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}