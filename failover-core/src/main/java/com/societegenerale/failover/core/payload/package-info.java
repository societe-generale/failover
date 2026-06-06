/**
 * Payload envelope, enrichment, and post-recovery handling.
 *
 * <p>{@link com.societegenerale.failover.core.payload.ReferentialPayload} is the store envelope
 * that wraps a business payload with identity, timestamps, and an {@code upToDate} flag.
 * {@code PayloadEnricher} populates domain objects on store and recover.
 * {@code RecoveredPayloadHandler} transforms the payload after recovery.
 */
package com.societegenerale.failover.core.payload;
