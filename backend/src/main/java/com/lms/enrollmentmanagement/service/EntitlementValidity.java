package com.lms.enrollmentmanagement.service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * {@code content_entitlement.valid_until} for a new grant.
 *
 * <p>Monthly classes rule (owner, 2026-11-09, supersedes plan D10 for MONTHLY):
 * a MONTHLY entitlement ends when its calendar month ends in the tenant
 * timezone, whatever the payment date. If that month has already ended at grant
 * time (a slip approved after month end, a purchase of past recordings) it falls
 * back to Payment Valid Days ({@code course.access_duration_days}) from the
 * grant, so a confirmed payment always grants usable access. MODULE and LESSON
 * entitlements keep Payment Valid Days from the grant. {@code null} = no limit.
 */
final class EntitlementValidity {

	private EntitlementValidity() {
	}

	static Instant validUntil(String scope, Integer periodYear, Integer periodMonth, Instant grantedAt, ZoneId zone,
			Integer accessDurationDays) {
		if ("MONTHLY".equals(scope) && periodYear != null && periodMonth != null) {
			Instant monthEnd = YearMonth.of(periodYear, periodMonth).plusMonths(1).atDay(1).atStartOfDay(zone)
				.toInstant();
			if (monthEnd.isAfter(grantedAt)) {
				return monthEnd;
			}
		}
		return accessDurationDays == null ? null : grantedAt.plus(Duration.ofDays(accessDurationDays));
	}

}
