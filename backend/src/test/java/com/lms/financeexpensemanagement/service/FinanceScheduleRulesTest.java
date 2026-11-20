package com.lms.financeexpensemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import com.lms.financeexpensemanagement.domain.FinanceScheduledTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FIN-01 calendar policy: short months are skipped, missed runs are caught up. */
class FinanceScheduleRulesTest {

	private static FinanceScheduledTransaction monthly(int dayOfMonth, LocalDate startsOn) {
		return new FinanceScheduledTransaction(UUID.randomUUID(), "Rent", TransactionType.EXPENSE,
				new BigDecimal("100.00"), "LKR", UUID.randomUUID(), null, UUID.randomUUID(), null,
				ScheduleType.MONTHLY, null, dayOfMonth, startsOn, UUID.randomUUID());
	}

	private static FinanceScheduledTransaction oneTime(LocalDate date, LocalDate startsOn) {
		return new FinanceScheduledTransaction(UUID.randomUUID(), "Deposit", TransactionType.INCOME,
				new BigDecimal("100.00"), "LKR", null, UUID.randomUUID(), UUID.randomUUID(), null,
				ScheduleType.ONE_TIME, date, null, startsOn, UUID.randomUUID());
	}

	@Test
	void the31stSkipsShortMonthsAndCatchesUpMissedRuns() {
		FinanceScheduledTransaction schedule = monthly(31, LocalDate.of(2026, 1, 10));
		List<LocalDate> due = FinanceScheduleService.dueDatesUpTo(schedule, LocalDate.of(2026, 6, 15));
		assertThat(due).containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31),
				LocalDate.of(2026, 5, 31));
		assertThat(FinanceScheduleService.skippedMonths(schedule)).containsExactly("Feb", "Apr", "Jun", "Sep",
				"Nov");
		assertThat(FinanceScheduleService.nextDueDate(schedule, LocalDate.of(2026, 6, 15)))
			.isEqualTo(LocalDate.of(2026, 7, 31));
	}

	@Test
	void theFirstOccurrenceIsNeverBeforeTheStartDate() {
		FinanceScheduledTransaction schedule = monthly(5, LocalDate.of(2026, 3, 20));
		assertThat(FinanceScheduleService.dueDatesUpTo(schedule, LocalDate.of(2026, 5, 4)))
			.containsExactly(LocalDate.of(2026, 4, 5));
		assertThat(FinanceScheduleService.nextDueDate(schedule, LocalDate.of(2026, 5, 5)))
			.isEqualTo(LocalDate.of(2026, 5, 5));
		assertThat(FinanceScheduleService.skippedMonths(schedule)).isEmpty();
		assertThat(FinanceScheduleService.skippedMonths(monthly(29, LocalDate.of(2026, 1, 1))))
			.containsExactly("Feb (common years)");
		assertThat(FinanceScheduleService.skippedMonths(monthly(30, LocalDate.of(2026, 1, 1))))
			.containsExactly("Feb");
	}

	@Test
	void oneTimeSchedulesFallDueOnceOnOrAfterTheirDate() {
		FinanceScheduledTransaction future = oneTime(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1));
		assertThat(FinanceScheduleService.dueDatesUpTo(future, LocalDate.of(2026, 11, 15))).isEmpty();
		assertThat(FinanceScheduleService.nextDueDate(future, LocalDate.of(2026, 11, 15)))
			.isEqualTo(LocalDate.of(2026, 12, 1));
		FinanceScheduledTransaction past = oneTime(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
		assertThat(FinanceScheduleService.dueDatesUpTo(past, LocalDate.of(2026, 11, 15)))
			.containsExactly(LocalDate.of(2026, 11, 1));
		assertThat(FinanceScheduleService.nextDueDate(past, LocalDate.of(2026, 11, 16))).isNull();
	}

}
