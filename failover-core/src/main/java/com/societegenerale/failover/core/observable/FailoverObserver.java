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

package com.societegenerale.failover.core.observable;

import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;

/**
 * Publishes a startup summary of all detected {@code @Failover} configurations.
 *
 * @author Anand Manissery
 */
public interface FailoverObserver {

    /** Observes all registered {@code @Failover} configurations and publishes to {@link ObservablePublisher} instances. */
    void observe();
}
