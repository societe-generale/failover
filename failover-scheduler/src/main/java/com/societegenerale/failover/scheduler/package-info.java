/**
 * Scheduled tasks for periodic reporting and expiry cleanup.
 *
 * <p>{@link com.societegenerale.failover.scheduler.ObservableScheduler} publishes the failover
 * startup report on a configurable cron.  {@code ExpiryCleanupScheduler} evicts expired
 * entries from the store on a separate cron.  Both schedules are controlled by
 * {@code failover.scheduler.*} properties.
 */
package com.societegenerale.failover.scheduler;
