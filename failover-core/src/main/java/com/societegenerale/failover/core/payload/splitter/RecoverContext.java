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

package com.societegenerale.failover.core.payload.splitter;

import com.societegenerale.failover.annotations.Failover;
import lombok.Builder;
import lombok.Data;

import java.util.List;


/**
 * Mutable context passed to {@link PayloadSplitter#splitOnRecover} and {@link PayloadSplitter#merge}.
 *
 * <p>Mutable (unlike {@link StoreContext}) so that the recovered payload can be set on the
 * slice context via {@code setPayload(T)} before passing the list to
 * {@link PayloadSplitter#merge}.
 *
 * @param <T> the payload type for this context
 * @author Anand Manissery
 * @see PayloadSplitter
 */

@Builder
@Data
public class RecoverContext<T> {

    Failover failover;

    List<Object> args;

    Class<T> clazz;

    Throwable cause;

    T payload;
}
