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

package com.societegenerale.failover.store.jdbc.serializer;

import com.societegenerale.failover.core.store.FailoverStoreException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.Supplier;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.lang.Class.forName;

/**
 * Jackson-backed {@link Serializer} that converts payloads to/from JSON using a provided
 * {@link ObjectMapper}.
 *
 * <p>All methods are null-safe: a {@code null} input returns {@code null} without delegating
 * to the underlying {@code ObjectMapper}.
 *
 * <h2>Payload class allowlist</h2>
 * <p>{@link #toClass} loads a class from a name read back from the failover store. Because that
 * value is persisted data, an allowlist restricts which classes may be loaded: a class name is
 * accepted only if it exactly matches an entry or lives under an entry treated as a package prefix
 * (entry {@code "com.acme"} accepts {@code "com.acme.Country"} and {@code "com.acme.ref.Currency"}).
 *
 * <p>The allowlist is supplied lazily via a {@link Supplier} and resolved (and memoized) on first
 * use — this lets the auto-configuration feed in the {@code @Failover} payload packages discovered
 * by the startup scanner, which only completes after all singletons are built. An empty resolved
 * allowlist preserves the historical allow-all behaviour.
 *
 * @author Anand Manissery
 * @see Serializer
 */
@Slf4j
public class JsonSerializer implements Serializer {

    private final ObjectMapper objectMapper;

    private final Supplier<List<String>> allowedPayloadClassesSupplier;

    /**
     * When {@code true}, an empty resolved allowlist denies all deserialization (fail-closed) instead
     * of disabling the restriction (allow-all). See {@code failover.store.jdbc.strict-allowlist} (A3).
     */
    private final boolean strict;

    /** Memoized resolved allowlist; resolved once on first {@link #toClass} call. */
    @Nullable
    private volatile List<String> resolvedAllowedPayloadClasses;

    /**
     * Creates a serializer with no payload class restriction (allow-all).
     *
     * @param objectMapper the Jackson mapper used for all conversions
     */
    public JsonSerializer(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    /**
     * Creates a serializer that restricts {@link #toClass} to the given fixed allowlist.
     *
     * @param objectMapper          the Jackson mapper used for all conversions
     * @param allowedPayloadClasses exact class names or package prefixes permitted for
     *                              {@link #toClass}; an empty list disables the restriction
     */
    public JsonSerializer(ObjectMapper objectMapper, List<String> allowedPayloadClasses) {
        this(objectMapper, () -> allowedPayloadClasses);
    }

    /**
     * Creates a serializer whose allowlist is resolved lazily on first use (allow-all on empty).
     *
     * @param objectMapper                  the Jackson mapper used for all conversions
     * @param allowedPayloadClassesSupplier supplies the effective allowlist (exact class names or
     *                                      package prefixes); invoked once and memoized. An empty
     *                                      result disables the restriction
     */
    public JsonSerializer(ObjectMapper objectMapper, Supplier<List<String>> allowedPayloadClassesSupplier) {
        this(objectMapper, allowedPayloadClassesSupplier, false);
    }

    /**
     * Creates a serializer whose allowlist is resolved lazily on first use, with a configurable
     * empty-allowlist behaviour.
     *
     * @param objectMapper                  the Jackson mapper used for all conversions
     * @param allowedPayloadClassesSupplier supplies the effective allowlist (exact class names or
     *                                      package prefixes); invoked once and memoized
     * @param strict                        when {@code true}, an empty resolved allowlist denies all
     *                                      deserialization (fail-closed); when {@code false}, an empty
     *                                      allowlist disables the restriction (allow-all, legacy default)
     */
    public JsonSerializer(ObjectMapper objectMapper, Supplier<List<String>> allowedPayloadClassesSupplier, boolean strict) {
        this.objectMapper = objectMapper;
        this.allowedPayloadClassesSupplier = allowedPayloadClassesSupplier;
        this.strict = strict;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ObjectMapper#writeValueAsString}.
     */
    @Override
    public @Nullable <T> String serialize(@Nullable T payload) {
        if(payload == null) {
            return null;
        }
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ObjectMapper#readValue(String, Class)}.
     */
    @Override
    public @Nullable <T> T deserialize(@Nullable String payload, Class<T> clazz) {
        if (payload == null || clazz == null) {
            return null;
        }
        return objectMapper.readValue(payload, clazz);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable <T> String toClassName(@Nullable T payload) {
        if(payload == null) {
            return null;
        }
        return payload.getClass().getName();
    }

    /**
     * {@inheritDoc}
     *
     * @throws FailoverStoreException if the class name is rejected by the configured allowlist,
     *         or if the class cannot be found on the classpath (e.g. the payload class was
     *         renamed or removed between deployments while rows referencing it remain in the store)
     */
    @Override
    public @Nullable <T> Class<T> toClass(@Nullable String className) {
        if(className == null) {
            return null;
        }
        if (!isAllowed(className)) {
            throw new FailoverStoreException(
                    "Payload class '%s' read from the failover store is not in the allowlist (auto-derived from @Failover payload packages plus failover.store.jdbc.allowed-payload-classes). Refusing to load it."
                            .formatted(className));
        }
        try {
            return cast(forName(className));
        } catch (ClassNotFoundException e) {
            throw new FailoverStoreException(
                    "Payload class '%s' read from the failover store is not on the classpath. The class may have been renamed or removed since the row was stored; clean the stale rows or restore the class."
                            .formatted(className), e);
        }
    }

    private boolean isAllowed(String className) {
        List<String> allowed = resolvedAllowedPayloadClasses();
        if (allowed.isEmpty()) {
            // Empty allowlist: fail-closed (deny all) in strict mode, else legacy allow-all.
            return !strict;
        }
        return allowed.stream()
                .anyMatch(entry -> className.equals(entry) || className.startsWith(entry + "."));
    }

    /** Resolves the allowlist from the supplier once and memoizes it (double-checked). */
    private List<String> resolvedAllowedPayloadClasses() {
        List<String> resolved = resolvedAllowedPayloadClasses;
        if (resolved == null) {
            synchronized (this) {
                resolved = resolvedAllowedPayloadClasses;
                if (resolved == null) {
                    List<String> supplied = allowedPayloadClassesSupplier.get();
                    resolved = supplied == null ? List.of() : List.copyOf(supplied);
                    resolvedAllowedPayloadClasses = resolved;
                    logGrantSummary(resolved); // emit the audit summary exactly once, on first resolution
                }
            }
        }
        return resolved;
    }

    /**
     * Logs the effective deserialization allowlist once it is resolved (audit I-02). An empty list is a
     * fail-open allow-all and is surfaced at WARN; package-prefix grants are broader than exact class
     * names (they trust every class under the namespace read back from store data) and are also flagged
     * at WARN so an over-broad grant is visible and auditable rather than silent. Purely observational —
     * never alters the allowlist or fails resolution.
     */
    private void logGrantSummary(List<String> resolved) {
        if (resolved.isEmpty()) {
            if (strict) {
                log.error("Failover deserialization allowlist is EMPTY and strict mode is ON (failover.store.jdbc.strict-allowlist=true) "
                        + "— ALL payload deserialization will be DENIED (fail-closed). No @Failover payload types were discovered and "
                        + "failover.store.jdbc.allowed-payload-classes is empty; recovery from the JDBC store will fail until the allowlist is populated.");
                return;
            }
            log.warn("Failover deserialization allowlist is EMPTY — every payload class read back from the "
                    + "store will be loaded (allow-all). Set failover.store.jdbc.allowed-payload-classes, enable "
                    + "failover.store.jdbc.strict-allowlist=true to fail closed, or rely on @Failover scanning to "
                    + "restrict which classes may be deserialized.");
            return;
        }
        List<String> prefixGrants = resolved.stream().filter(entry -> !isLoadableClass(entry)).toList();
        long exactGrants = resolved.size() - prefixGrants.size();
        log.info("Failover deserialization allowlist resolved: {} exact class grant(s), {} package-prefix grant(s).",
                exactGrants, prefixGrants.size());
        if (!prefixGrants.isEmpty()) {
            log.warn("Failover deserialization allowlist contains {} package-prefix grant(s): {}. A prefix grant "
                    + "permits EVERY class under that namespace to be deserialized from store data — broader than an "
                    + "exact class name. Prefer listing exact payload class names in failover.store.jdbc.allowed-payload-classes "
                    + "where possible.", prefixGrants.size(), prefixGrants);
        }
    }

    /** True when {@code name} resolves to a loadable class (an exact-class grant); false marks a package-prefix grant. */
    private boolean isLoadableClass(String name) {
        try {
            Class.forName(name, false, getClass().getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
