/**
 * Spring Boot failure analyser for missing failover bean definitions.
 *
 * <p>{@link com.societegenerale.failover.analyzer.FailoverFailureAnalyzer} intercepts
 * {@link org.springframework.beans.factory.NoSuchBeanDefinitionException} at startup
 * and emits a human-readable diagnostic when a required failover bean (key generator,
 * expiry policy, or payload splitter) is not registered.
 */
package com.societegenerale.failover.analyzer;
