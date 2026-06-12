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

/**
 * Strategy for resolving a {@link PayloadSplitter} by qualifier or bean name.
 *
 * <p>Used by {@link com.societegenerale.failover.core.ScatterGatherFailoverHandler} to locate
 * the {@link PayloadSplitter} named in
 * {@link com.societegenerale.failover.annotations.Failover#payloadSplitter()}.
 *
 * @param <T> the composite payload type to split
 * @param <R> the slice type produced by the splitter
 * @author Anand Manissery
 * @see PayloadSplitter
 */
public interface PayloadSplitterLookup<T, R> {

    /**
     * Returns the {@link PayloadSplitter} registered under {@code name}, or {@code null} if not found.
     *
     * @param name qualifier or bean name as declared in {@code @Failover(payloadSplitter = "...")}
     * @return matching {@link PayloadSplitter}, or {@code null} if no match exists
     */
    PayloadSplitter<T, R> lookup(String name);
}