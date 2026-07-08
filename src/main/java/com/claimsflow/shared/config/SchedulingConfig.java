package com.claimsflow.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Central scheduling switch for all background jobs: outbox relay, outbox
 * retry, search reconciliation, and dashboard broadcast.
 *
 * <p>Kept in one place (rather than on an arbitrary scheduled component) so
 * removing any single job never silently disables the others. Pool size is
 * configured via {@code spring.task.scheduling.pool.size} — the default pool
 * of 1 thread would serialize all jobs behind each other.
 *
 * <p>Disabled in the test profile — tests invoke job methods directly.
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
