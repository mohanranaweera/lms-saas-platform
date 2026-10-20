package com.lms.tenantmanagement.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * The tenant facility-fee policy read from {@link ConfigDomain#PAYMENT}
 * ({@code facility_fee_mode} + {@code facility_fee_amount}, KLASS-T4c D07 and
 * the multi-course checkout plan §2). One parser shared by the payment
 * composer (pricing) and course management (admin display), so both always
 * agree on the mode and the tenant default.
 *
 * <p>{@code mode} is one of {@link #MODES}; anything unrecognized resolves to
 * {@code NONE}. {@code amount} is the tenant default fee, scale 2, never
 * negative ({@code 0} when unset or invalid).
 */
public record FacilityFeeSettings(String mode, BigDecimal amount) {

	public static final String NONE = "NONE";

	public static final String PER_COURSE = "PER_COURSE";

	public static final String PER_LINE = "PER_LINE";

	public static final String PER_ORDER = "PER_ORDER";

	public static final List<String> MODES = List.of(NONE, PER_COURSE, PER_LINE, PER_ORDER);

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	/** @param paymentConfig the resolved {@code PAYMENT} config domain (may be {@code null}). */
	public static FacilityFeeSettings from(Map<String, Object> paymentConfig) {
		Map<String, Object> config = paymentConfig == null ? Map.of() : paymentConfig;
		Object mode = config.get("facility_fee_mode");
		String resolvedMode = (mode instanceof String s && MODES.contains(s)) ? s : NONE;
		BigDecimal amount = ZERO;
		Object raw = config.get("facility_fee_amount");
		if (raw instanceof Number number) {
			try {
				amount = new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP).max(ZERO);
			}
			catch (NumberFormatException ex) {
				amount = ZERO;
			}
		}
		return new FacilityFeeSettings(resolvedMode, amount);
	}

	/** Whether a course-level {@code facility_fee_override} takes part in this mode (PER_COURSE / PER_LINE). */
	public boolean courseOverrideApplies() {
		return PER_COURSE.equals(mode) || PER_LINE.equals(mode);
	}

	/**
	 * The facility fee one course is charged per its basis unit: {@code 0}
	 * for {@code NONE}; the tenant default for {@code PER_ORDER} (overrides
	 * never apply); otherwise the course override when NOT NULL (0 = no fee -
	 * a null check only, never a zero/signum fallback), else the tenant
	 * default.
	 */
	public BigDecimal effectiveFor(BigDecimal courseOverride) {
		if (NONE.equals(mode)) {
			return ZERO;
		}
		if (!courseOverrideApplies() || courseOverride == null) {
			return amount;
		}
		return courseOverride.setScale(2, RoundingMode.HALF_UP);
	}

	/** {@code COURSE_OVERRIDE} / {@code TENANT_DEFAULT}, or {@code NOT_APPLICABLE} for NONE / PER_ORDER. */
	public String sourceFor(BigDecimal courseOverride) {
		if (!courseOverrideApplies()) {
			return "NOT_APPLICABLE";
		}
		return courseOverride != null ? "COURSE_OVERRIDE" : "TENANT_DEFAULT";
	}

	/** The order {@code fee_policy_snapshot}: {@code MODE:amount} (tenant default) or {@code NONE}. */
	public String snapshot() {
		return NONE.equals(mode) ? NONE : mode + ":" + amount.toPlainString();
	}

}
