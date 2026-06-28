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

/**
 * Resolves the unique identity string for this application instance, used to key per-instance
 * data in the failover dashboard (snapshot store, Instances tab).
 *
 * <p>The default implementation ({@code DefaultInstanceIdResolver}) produces
 * {@code <spring.application.name>:<hostname>:<port>}, resolved lazily on each call so
 * {@code local.server.port} (set after the embedded server starts) is always captured correctly,
 * including when {@code server.port=0} (random port).
 *
 * <p>Declare a bean of this type to override: useful for k8s pod names, Docker container ids,
 * or an explicit property value such as {@code failover.observable.instance.id}.
 *
 * @author Anand Manissery
 */
@FunctionalInterface
public interface InstanceIdResolver {

    /** Returns a stable, unique string identifying this instance (e.g. {@code myapp:host-1:8080}). */
    String resolve();
}
