package com.lms.notificationmanagement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} infrastructure so {@code
 * NotificationDispatchPoller#pollAndDispatch} actually runs. This is the
 * FIRST use of {@code @Scheduled}/{@code @EnableScheduling} anywhere in this
 * codebase - no other domain has needed a background scheduled job before
 * this one (confirmed: {@code EnrollmentReconciliationApi}'s own javadoc
 * explicitly notes no scheduler exists yet). The default Spring
 * Boot-auto-configured task scheduler is sufficient for a single {@code
 * @Scheduled} method at this scale - no custom {@code TaskScheduler}/
 * thread-pool bean is added here since nothing has asked for one.
 */
@Configuration
@EnableScheduling
public class NotificationSchedulingConfig {

}
