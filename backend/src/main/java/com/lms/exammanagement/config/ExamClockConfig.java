package com.lms.exammanagement.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} every exam-management service uses for "current
 * time" checks (window/lifecycle evaluation) - MVP-017 plan §9 explicitly
 * requires an injected {@code Clock} rather than {@code Instant.now()}
 * directly, so window-boundary behavior stays unit-testable. No {@code
 * Clock} bean exists anywhere else in this codebase today (confirmed absent
 * platform-wide before adding this), so this is the first and only one - a
 * future module needing the same testability should reuse this bean rather
 * than defining a second one.
 */
@Configuration
public class ExamClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
