/**
 * Payload serialization / deserialization for the JDBC store.
 *
 * <p>{@link com.societegenerale.failover.store.jdbc.serializer.Serializer} converts business
 * payloads to and from {@code String} for persistence.  The default implementation uses
 * Jackson JSON.
 */
package com.societegenerale.failover.store.jdbc.serializer;
