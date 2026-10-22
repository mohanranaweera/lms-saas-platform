package com.lms.reportinganalytics.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.SortedSet;
import java.util.UUID;

/**
 * Validated RPT-01 filter (output of {@link TeacherIncomeRequestValidator}).
 * Only the active period mode's fields are set: MONTHS carries {@code months}
 * (1..36, sorted), DATES carries {@code startDate}/{@code endDate} (inclusive,
 * tenant timezone). {@code teacherIds} are verified TEACHER users of the
 * caller's tenant, de-duplicated in request order. {@code paymentMethod} null
 * means all cash methods.
 */
public record TeacherIncomeCriteria(List<UUID> teacherIds, PaymentMethod paymentMethod, PeriodMode periodMode,
		SortedSet<YearMonth> months, LocalDate startDate, LocalDate endDate) {

	public enum PeriodMode {

		MONTHS, DATES

	}

}
