package com.lms.identityaccessservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Wave 10 device settings. {@code planDefaultLimit} is the lowest level of the
 * device-limit precedence (student, course, tenant, plan) - there is no SaaS
 * plan entity yet, so the plan default is platform configuration (product
 * owner decision D2: 2 devices per student). {@code cookieMaxAge} is the
 * lifetime of the {@code lms_device_id} cookie (400 days, the browser cap).
 */
@Component
@ConfigurationProperties(prefix = "app.security.device")
public class DeviceProperties {

	private int planDefaultLimit = 2;

	private Duration cookieMaxAge = Duration.ofDays(400);

	public int getPlanDefaultLimit() {
		return planDefaultLimit;
	}

	public void setPlanDefaultLimit(int planDefaultLimit) {
		this.planDefaultLimit = planDefaultLimit;
	}

	public Duration getCookieMaxAge() {
		return cookieMaxAge;
	}

	public void setCookieMaxAge(Duration cookieMaxAge) {
		this.cookieMaxAge = cookieMaxAge;
	}

}
