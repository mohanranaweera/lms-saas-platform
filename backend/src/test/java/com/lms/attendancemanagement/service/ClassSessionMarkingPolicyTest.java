package com.lms.attendancemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.liveclassmanagement.api.ClassSessionSummary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-function coverage of the Wave 8 session-lifecycle marking gate. */
class ClassSessionMarkingPolicyTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

	private static ClassSessionSummary session(String status, Instant start) {
		return new ClassSessionSummary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "t", start,
				start.plus(1, ChronoUnit.HOURS), status);
	}

	@Test
	void liveAndCompletedAreOpenRegardlessOfScheduledStart() {
		Instant future = NOW.plus(1, ChronoUnit.DAYS);
		assertThat(ClassSessionMarkingPolicy.closedReason(session("LIVE", future), NOW)).isEmpty();
		assertThat(ClassSessionMarkingPolicy.closedReason(session("COMPLETED", future), NOW)).isEmpty();
	}

	@Test
	void scheduledIsOpenOnlyOnceItsStartHasPassed() {
		assertThat(ClassSessionMarkingPolicy.closedReason(session("SCHEDULED", NOW.minusSeconds(1)), NOW)).isEmpty();
		assertThat(ClassSessionMarkingPolicy.closedReason(session("SCHEDULED", NOW), NOW)).isEmpty();
		assertThat(ClassSessionMarkingPolicy.closedReason(session("SCHEDULED", NOW.plusSeconds(1)), NOW))
			.contains(ClassSessionMarkingPolicy.NOT_STARTED_REASON);
	}

	@Test
	void cancelledIsAlwaysClosed() {
		assertThat(ClassSessionMarkingPolicy.closedReason(session("CANCELLED", NOW.minus(1, ChronoUnit.DAYS)), NOW))
			.contains(ClassSessionMarkingPolicy.CANCELLED_REASON);
	}

	@Test
	void attendanceRateCountsLateAsAttendedAndRoundsToOneDecimal() {
		assertThat(AttendanceSummaryView.rateOf(1, 1, 3)).isEqualByComparingTo("66.7");
		assertThat(AttendanceSummaryView.rateOf(0, 0, 4)).isEqualByComparingTo("0.0");
		assertThat(AttendanceSummaryView.rateOf(2, 0, 2)).isEqualByComparingTo("100.0");
	}

}
