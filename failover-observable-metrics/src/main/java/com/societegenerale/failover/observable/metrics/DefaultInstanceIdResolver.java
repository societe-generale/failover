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

package com.societegenerale.failover.observable.metrics;

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.function.Supplier;

/**
 * Default {@link InstanceIdResolver}: produces {@code <appName>:<hostname>:<port>}.
 *
 * <p>Port is supplied lazily via {@code portSupplier} so {@code local.server.port} (set by Spring Boot
 * after the embedded server starts) is captured correctly, including when {@code server.port=0} (random port).
 *
 * <p>Callers resolve {@code appName} and {@code portSupplier} from {@code Environment}; this class has
 * no Spring dependency. Use {@link #resolveHostname()} for the hostname argument.
 *
 * <p>Override by registering a custom {@link InstanceIdResolver} bean — e.g. to use a k8s pod name,
 * a Docker container id, or the value of {@code failover.observable.instance.id}.
 *
 * @author Anand Manissery
 */
public class DefaultInstanceIdResolver implements InstanceIdResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultInstanceIdResolver.class);

    private final String appName;
    private final String hostname;
    private final Supplier<String> portSupplier;

    public DefaultInstanceIdResolver(String appName, String hostname, Supplier<String> portSupplier) {
        this.appName = appName;
        this.hostname = hostname;
        this.portSupplier = portSupplier;
    }

    @Override
    public String resolve() {
        return appName + ":" + hostname + ":" + portSupplier.get();
    }

    /**
     * Resolves the local hostname, falling back to {@code "unknown-host"} when the network stack
     * is unavailable. Provided as a static helper so callers can use it without duplicating the
     * try/catch.
     */
    public static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.debug("Cannot resolve hostname for instance id; falling back to 'unknown-host'.", e);
            return "unknown-host";
        }
    }
}
