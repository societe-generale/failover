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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.net.InetAddress;

/**
 * Default {@link InstanceIdResolver}: produces {@code <spring.application.name>:<hostname>:<port>}.
 *
 * <p>Port is resolved lazily (on every {@link #resolve()} call) in the order:
 * <ol>
 *   <li>{@code local.server.port} — set by Spring Boot after the embedded server starts; correct
 *       even when {@code server.port=0} (random port).</li>
 *   <li>{@code server.port} — the configured value.</li>
 *   <li>{@code 8080} — hard fallback.</li>
 * </ol>
 *
 * <p>Including port distinguishes same-machine multi-instance deployments where hostname alone would
 * collide. Resolution is lazy so the correct port is captured after server start, including the
 * actual port when {@code server.port=0} assigns a random one.
 *
 * <p>Hostname falls back to {@code unknown-host} when the network stack is unavailable.
 *
 * <p>Override by registering a custom {@link InstanceIdResolver} bean — e.g. to use a k8s pod name,
 * a Docker container id, or the value of {@code failover.observable.instance.id}.
 *
 * @author Anand Manissery
 */
@Slf4j
public class DefaultInstanceIdResolver implements InstanceIdResolver {

    private final Environment environment;

    public DefaultInstanceIdResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolve() {
        String app = environment.getProperty("spring.application.name", "application");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.debug("Cannot resolve hostname for instance id; falling back to 'unknown-host'.", e);
            host = "unknown-host";
        }
        // local.server.port is available only after the embedded server has started, so we read it
        // lazily here (not at bean construction) — this captures the actual port, including port=0.
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        return app + ":" + host + ":" + port;
    }
}
