package com.lms.enrollmentmanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.enrollmentmanagement.api.CoverageKeys;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-030 (F3/F4/F8): Free Card validity, status, covered months and once-only revocation. */
class FreeCardTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	private static final UUID COURSE = UUID.randomUUID();

	private static FreeCard card(LocalDate from, LocalDate to) {
		return FreeCard.issue(UUID.randomUUID(), UUID.randomUUID(), COURSE, FreeCardType.FULL, from, to, COLOMBO,
				" scholarship ", "LKR", new BigDecimal("3000"), UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
				false);
	}

	@Test
	void effectiveWindowIsStartOfFirstDayToStartOfDayAfterLastDayInTheSnapshotZone() {
		FreeCard card = card(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20));

		assertThat(card.getTimezone()).isEqualTo("Asia/Colombo");
		assertThat(card.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 10).atStartOfDay(COLOMBO).toInstant());
		assertThat(card.getEffectiveUntil()).isEqualTo(LocalDate.of(2026, 8, 21).atStartOfDay(COLOMBO).toInstant());
		assertThat(card.getReason()).isEqualTo("scholarship");
	}

	@Test
	void statusMovesScheduledActiveExpiredAndIsActiveOnlyInsideTheHalfOpenWindow() {
		FreeCard card = card(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20));
		Instant from = card.getEffectiveFrom();
		Instant until = card.getEffectiveUntil();

		assertThat(card.statusAt(from.minusSeconds(1))).isEqualTo(FreeCard.Status.SCHEDULED);
		assertThat(card.statusAt(from)).isEqualTo(FreeCard.Status.ACTIVE);
		assertThat(card.statusAt(until.minusSeconds(1))).isEqualTo(FreeCard.Status.ACTIVE);
		assertThat(card.statusAt(until)).isEqualTo(FreeCard.Status.EXPIRED);
		assertThat(card.isEffectiveAt(from)).isTrue();
		assertThat(card.isEffectiveAt(until)).isFalse();
	}

	@Test
	void coveredMonthsSpanTheYearBoundaryAndCarryTheCardsOwnCourse() {
		FreeCard card = card(LocalDate.of(2026, 11, 30), LocalDate.of(2027, 2, 1));

		assertThat(card.coveredMonths()).containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12),
				YearMonth.of(2027, 1), YearMonth.of(2027, 2));
		assertThat(card.coveredMonthKeys()).containsExactly(CoverageKeys.monthly(COURSE, 2026, 11),
				CoverageKeys.monthly(COURSE, 2026, 12), CoverageKeys.monthly(COURSE, 2027, 1),
				CoverageKeys.monthly(COURSE, 2027, 2));
		assertThat(FreeCard.coveredMonthCount(LocalDate.of(2026, 11, 30), LocalDate.of(2027, 2, 1))).isEqualTo(4);
		assertThat(card.getNominalMonthlyAmount()).isEqualByComparingTo("3000.00");
		assertThat(card.getNominalValue()).isEqualByComparingTo("12000.00");
		assertThat(card.getNominalValue().scale()).isEqualTo(2);
	}

	@Test
	void singleDayCardCoversExactlyItsMonth() {
		FreeCard card = card(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

		assertThat(card.coveredMonths()).containsExactly(YearMonth.of(2026, 8));
	}

	@Test
	void revokeOnceThenRefusedAndStatusIsRevoked() {
		FreeCard card = card(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));
		UUID actor = UUID.randomUUID();
		Instant at = card.getEffectiveFrom().plusSeconds(60);

		card.revoke(actor, at, " left the class ");

		assertThat(card.isRevoked()).isTrue();
		assertThat(card.getRevokedBy()).isEqualTo(actor);
		assertThat(card.getRevokedAt()).isEqualTo(at);
		assertThat(card.getRevokedReason()).isEqualTo("left the class");
		assertThat(card.statusAt(at.plusSeconds(1))).isEqualTo(FreeCard.Status.REVOKED);
		assertThat(card.isEffectiveAt(at.plusSeconds(1))).isFalse();
		assertThatThrownBy(() -> card.revoke(UUID.randomUUID(), at.plusSeconds(5), "again"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(card.getRevokedBy()).isEqualTo(actor);
	}

	@Test
	void revokeRequiresAReason() {
		FreeCard card = card(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

		assertThatThrownBy(() -> card.revoke(UUID.randomUUID(), Instant.now(), "  "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(card.isRevoked()).isFalse();
	}

	@Test
	void reversedRangeAndBlankReasonAreRefused() {
		assertThatThrownBy(() -> card(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FreeCard.issue(UUID.randomUUID(), UUID.randomUUID(), COURSE, FreeCardType.FULL,
				LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), COLOMBO, " ", "LKR", null, UUID.randomUUID(),
				Instant.now(), UUID.randomUUID(), false))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void overlapIsInclusiveOnBothEnds() {
		FreeCard card = card(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20));

		assertThat(card.overlaps(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 1))).isTrue();
		assertThat(card.overlaps(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10))).isTrue();
		assertThat(card.overlaps(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 1))).isFalse();
		assertThat(card.overlaps(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 9))).isFalse();
	}

	@Test
	void missingMonthlyPriceLeavesNominalValuesEmpty() {
		FreeCard card = FreeCard.issue(UUID.randomUUID(), UUID.randomUUID(), COURSE, FreeCardType.FULL,
				LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), COLOMBO, "x", "LKR", null, UUID.randomUUID(),
				Instant.now(), UUID.randomUUID(), true);

		assertThat(card.getNominalMonthlyAmount()).isNull();
		assertThat(card.getNominalValue()).isNull();
		assertThat(card.isEnrollmentCreated()).isTrue();
	}

}
