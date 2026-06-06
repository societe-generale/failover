/**
 * Spring JDBC {@link org.springframework.jdbc.core.RowMapper} for the failover store table.
 *
 * <p>{@link com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper} converts
 * a {@code FAILOVER_STORE} row into a
 * {@link com.societegenerale.failover.core.payload.ReferentialPayload}, delegating payload
 * column extraction to the configured
 * {@link com.societegenerale.failover.store.resolver.PayloadColumnResolver}.
 */
package com.societegenerale.failover.store.mapper;
