package com.lms.enrollmentmanagement.api;

import java.util.Locale;
import java.util.UUID;

/**
 * The {@code coverage_key} format shared by {@code content_entitlement} (V86)
 * and {@code student_order_line} (V87), enforced there by CHECK constraints:
 * {@code MONTHLY:<courseId>:<yyyy>-<mm>}, {@code MODULE:<moduleId>}, {@code
 * LESSON:<lessonId>} (UUIDs in canonical lower-case text form).
 */
public final class CoverageKeys {

	private CoverageKeys() {
	}

	public static String monthly(UUID courseId, int year, int month) {
		return "MONTHLY:" + courseId + ":" + String.format(Locale.ROOT, "%04d-%02d", year, month);
	}

	public static String module(UUID moduleId) {
		return "MODULE:" + moduleId;
	}

	public static String lesson(UUID lessonId) {
		return "LESSON:" + lessonId;
	}

	/**
	 * KLASS-T4c: the advisory-lock key shared by the payment composer (order
	 * creation) and the entitlement grant path, so both serialize on the same
	 * (tenant, student, coverage). {@code tenantId} must be the resolved
	 * context tenant.
	 */
	public static String lockKey(UUID tenantId, UUID studentId, String coverageKey) {
		return "coverage:" + tenantId + ":" + studentId + ":" + coverageKey;
	}

}
