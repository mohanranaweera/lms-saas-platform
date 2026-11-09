package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Monthly classes rule (owner, 2026-11-09, supersedes plan D10 for MONTHLY): a
 * MONTHLY entitlement ends with its calendar month in the tenant timezone,
 * whatever the payment date. A month that has already ended when the grant
 * happens (late slip approval, buying past recordings) falls back to Payment
 * Valid Days from the grant. MODULE/LESSON keep Payment Valid Days.
 */
class EntitlementValidityTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	private static Instant colombo(int year, int month, int day, int hour) {
		return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, COLOMBO).toInstant();
	}

	@Test
	void currentMonthEndsAtTheStartOfNextMonthInTheTenantZoneWhateverTheValidDays() {
		Instant granted = colombo(2026, 11, 9, 17);

		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 11, granted, COLOMBO, 30))
			.isEqualTo(colombo(2026, 12, 1, 0));
		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 11, granted, COLOMBO, 7))
			.as("short valid days never cut the paid month short")
			.isEqualTo(colombo(2026, 12, 1, 0));
		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 11, granted, COLOMBO, null))
			.as("no-limit course still ends with the month")
			.isEqualTo(colombo(2026, 12, 1, 0));
	}

	@Test
	void anAdvancePurchaseCoversTheWholeFutureMonth() {
		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 12, colombo(2026, 11, 20, 10), COLOMBO, 30))
			.isEqualTo(colombo(2027, 1, 1, 0));
	}

	@Test
	void theMonthBoundaryIsTheTenantsLocalMidnightNotUtc() {
		// 2026-11-30 20:00 UTC is already 2026-12-01 01:30 in Colombo: November is over there.
		Instant granted = Instant.parse("2026-11-30T20:00:00Z");

		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 12, granted, COLOMBO, 30))
			.isEqualTo(colombo(2027, 1, 1, 0));
		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 11, granted, COLOMBO, 30))
			.as("November already ended locally - falls back to valid days")
			.isEqualTo(granted.plus(java.time.Duration.ofDays(30)));
	}

	@Test
	void aMonthAlreadyOverAtGrantTimeFallsBackToValidDaysFromTheGrant() {
		Instant approvedLate = colombo(2026, 12, 2, 9);

		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 11, approvedLate, COLOMBO, 30))
			.isEqualTo(approvedLate.plus(java.time.Duration.ofDays(30)));
		assertThat(EntitlementValidity.validUntil("MONTHLY", 2026, 10, approvedLate, COLOMBO, null))
			.as("no limit configured")
			.isNull();
	}

	@Test
	void moduleAndLessonKeepValidDaysFromTheGrant() {
		Instant granted = colombo(2026, 11, 9, 17);

		assertThat(EntitlementValidity.validUntil("MODULE", null, null, granted, COLOMBO, 30))
			.isEqualTo(granted.plus(java.time.Duration.ofDays(30)));
		assertThat(EntitlementValidity.validUntil("LESSON", null, null, granted, COLOMBO, null)).isNull();
	}

}
