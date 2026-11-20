package com.lms.paymentmanagement.discount.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DISC-01 hardening settings (owner decisions 2026-10-21).
 * <ul>
 * <li>{@code codeAttempts.maxInvalid} / {@code codeAttempts.window}: per
 * (tenant, student) fixed-window limit on discount-code attempts that resolve
 * to {@code INVALID} on {@code /api/v1/checkout/quote} and {@code /orders};
 * beyond it the request is {@code 429 DISCOUNT_CODE_RATE_LIMITED}. Defaults: 10
 * per 15 minutes.</li>
 * <li>{@code gatewayHoldTtl}: how long a discount hold stays counted after a
 * gateway payment starts (D12) - default 48h. Bank-slip holds stay open-ended
 * while the slip is open (staff resolve them).</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.discount")
public class DiscountProperties {

	private final CodeAttempts codeAttempts = new CodeAttempts();

	private Duration gatewayHoldTtl = Duration.ofHours(48);

	public CodeAttempts getCodeAttempts() {
		return codeAttempts;
	}

	public Duration getGatewayHoldTtl() {
		return gatewayHoldTtl;
	}

	public void setGatewayHoldTtl(Duration gatewayHoldTtl) {
		this.gatewayHoldTtl = gatewayHoldTtl;
	}

	public static class CodeAttempts {

		private int maxInvalid = 10;

		private Duration window = Duration.ofMinutes(15);

		public int getMaxInvalid() {
			return maxInvalid;
		}

		public void setMaxInvalid(int maxInvalid) {
			this.maxInvalid = maxInvalid;
		}

		public Duration getWindow() {
			return window;
		}

		public void setWindow(Duration window) {
			this.window = window;
		}

	}

}
