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

package com.societegenerale.failover.core.payload.splitter;

import com.societegenerale.failover.annotations.Failover;

/**
 * Thrown when a user-provided {@link PayloadSplitter} raises an exception during
 * {@code splitOnStore}, {@code splitOnRecover}, or {@code merge}.
 *
 * <p>Wraps the original cause and includes the splitter bean name and the failover
 * annotation details to aid diagnosis.
 *
 * @author Anand Manissery
 * @see PayloadSplitter
 */
public class PayloadSplitterExecutionException extends RuntimeException {

    /**
     * Creates an execution exception with full context.
     *
     * @param operation   the splitter method that failed: {@code "splitOnStore"},
     *                    {@code "splitOnRecover"}, or {@code "merge"}
     * @param splitterName the bean name of the {@link PayloadSplitter} that threw
     * @param failover    the {@link Failover} annotation on the intercepted method
     * @param cause       the exception thrown by the splitter
     */
    public PayloadSplitterExecutionException(String operation, String splitterName, Failover failover, Throwable cause) {
        super("PayloadSplitter '%s' failed during '%s' for failover '%s' [payloadSplitter='%s', expiryDuration=%d, expiryUnit='%s', domain='%s']: %s"
                .formatted(splitterName, operation, failover.name(), failover.payloadSplitter(),
                        failover.expiryDuration(), failover.expiryUnit(), failover.domain(),
                        cause.getMessage()),
                cause);
    }
}