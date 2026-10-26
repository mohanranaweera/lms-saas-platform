package com.lms.reportinganalytics.service.support;

import com.lms.common.api.FieldError;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Shared by RPT-01 and RPT-02: the server-side period / payment-method filter
 * rules (MONTHS: 1..36 {@code YYYY-MM} months; DATES: inclusive {@code
 * YYYY-MM-DD} start/end, start &lt;= end, span &lt;= 366 days; only the active
 * mode's fields may be set), the period label, the tenant-timezone instant
 * windows and the 2-dp money string. Pure and stateless. Every problem is
 * appended as one {@link FieldError}; callers throw the {@code 422}.
 */
public final class ReportPeriodRules {

	public static final int MAX_MONTHS = 36;

	public static final long MAX_SPAN_DAYS = 366;

	/** The cash channels a report can be filtered by (never FREE / STAFF_GRANTED). */
	public static final List<PaymentMethod> FILTERABLE_METHODS = List.of(PaymentMethod.GATEWAY,
			PaymentMethod.MANUAL_SLIP, PaymentMethod.MANUAL_RECEIPT);

	private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

	private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	public enum Mode {

		MONTHS, DATES

	}

	/** A validated period: only the active mode's fields are set; months sorted and unmodifiable. */
	public record Period(Mode mode, SortedSet<YearMonth> months, LocalDate startDate, LocalDate endDate) {

	}

	private ReportPeriodRules() {
	}

	/** {@code null}/blank = all methods; otherwise one of {@link #FILTERABLE_METHODS}. */
	public static PaymentMethod parseMethod(String value, List<FieldError> errors) {
		if (!present(value)) {
			return null;
		}
		for (PaymentMethod method : FILTERABLE_METHODS) {
			if (method.name().equals(value.trim())) {
				return method;
			}
		}
		errors.add(new FieldError("paymentMethod", "must be one of GATEWAY, MANUAL_SLIP, MANUAL_RECEIPT"));
		return null;
	}

	/** @return the period, or {@code null} when the mode itself is missing / invalid (error added). */
	public static Period parsePeriod(String modeValue, List<String> monthValues, String startValue, String endValue,
			List<FieldError> errors) {
		Mode mode = parseMode(modeValue, errors);
		if (mode == null) {
			return null;
		}
		if (mode == Mode.MONTHS) {
			if (present(startValue)) {
				errors.add(new FieldError("startDate", "must not be set when periodMode is MONTHS"));
			}
			if (present(endValue)) {
				errors.add(new FieldError("endDate", "must not be set when periodMode is MONTHS"));
			}
			SortedSet<YearMonth> months = parseMonths(monthValues, errors);
			return new Period(mode, months == null ? null : Collections.unmodifiableSortedSet(months), null, null);
		}
		if (monthValues != null && !monthValues.isEmpty()) {
			errors.add(new FieldError("months", "must not be set when periodMode is DATES"));
		}
		LocalDate start = parseDate("startDate", startValue, errors);
		LocalDate end = parseDate("endDate", endValue, errors);
		if (start != null && end != null) {
			if (start.isAfter(end)) {
				errors.add(new FieldError("startDate", "must be on or before endDate"));
			}
			else if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_SPAN_DAYS) {
				errors.add(new FieldError("endDate", "the period must not exceed " + MAX_SPAN_DAYS + " days"));
			}
		}
		return new Period(mode, null, start, end);
	}

	private static Mode parseMode(String value, List<FieldError> errors) {
		if (!present(value)) {
			errors.add(new FieldError("periodMode", "is required (MONTHS or DATES)"));
			return null;
		}
		try {
			return Mode.valueOf(value.trim());
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError("periodMode", "must be MONTHS or DATES"));
			return null;
		}
	}

	private static SortedSet<YearMonth> parseMonths(List<String> values, List<FieldError> errors) {
		if (values == null || values.isEmpty()) {
			errors.add(new FieldError("months", "select at least one month"));
			return null;
		}
		SortedSet<YearMonth> months = new TreeSet<>();
		for (int i = 0; i < values.size(); i++) {
			YearMonth month = parseMonth(values.get(i));
			if (month == null) {
				errors.add(new FieldError("months[" + i + "]", "must be a month in YYYY-MM format"));
			}
			else {
				months.add(month);
			}
		}
		if (months.size() > MAX_MONTHS) {
			errors.add(new FieldError("months", "at most " + MAX_MONTHS + " months can be selected"));
		}
		return months;
	}

	private static YearMonth parseMonth(String value) {
		if (value == null || !MONTH.matcher(value).matches()) {
			return null;
		}
		try {
			YearMonth month = YearMonth.parse(value);
			return (month.getYear() < 2000 || month.getYear() > 2100) ? null : month;
		}
		catch (DateTimeException ex) {
			return null;
		}
	}

	private static LocalDate parseDate(String field, String value, List<FieldError> errors) {
		if (!present(value)) {
			errors.add(new FieldError(field, "is required when periodMode is DATES"));
			return null;
		}
		try {
			LocalDate date = LocalDate.parse(value.trim());
			if (date.getYear() < 2000 || date.getYear() > 2100) {
				errors.add(new FieldError(field, "must be between 2000 and 2100"));
				return null;
			}
			return date;
		}
		catch (DateTimeException ex) {
			errors.add(new FieldError(field, "must be a date in YYYY-MM-DD format"));
			return null;
		}
	}

	public static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	// ------------------------------------------------------------------
	// Labels, windows, money.
	// ------------------------------------------------------------------

	/**
	 * As {@link #periodLabel(Mode, Collection, LocalDate, LocalDate)}, but a
	 * single day is labelled once ("08 Oct 2026") - RPT-02.
	 */
	public static String compactPeriodLabel(Mode mode, Collection<YearMonth> months, LocalDate start,
			LocalDate end) {
		if (mode == Mode.DATES && start.equals(end)) {
			return start.format(DATE_LABEL);
		}
		return periodLabel(mode, months, start, end);
	}

	/** "Dec 2024, Jan 2025" (MONTHS) or "01 Mar 2025 to 31 Mar 2025" (DATES). */
	public static String periodLabel(Mode mode, Collection<YearMonth> months, LocalDate start, LocalDate end) {
		if (mode == Mode.MONTHS) {
			List<String> labels = new ArrayList<>();
			new TreeSet<>(months).forEach(month -> labels.add(month.format(MONTH_LABEL)));
			return String.join(", ", labels);
		}
		return start.format(DATE_LABEL) + " to " + end.format(DATE_LABEL);
	}

	/** {@code [start 00:00, end+1 00:00)} in the tenant timezone. */
	public static InstantRange dateWindow(LocalDate start, LocalDate end, ZoneId zone) {
		return new InstantRange(start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant());
	}

	/** One window per selected month in the tenant timezone, adjacent months merged. */
	public static List<InstantRange> monthWindows(Collection<YearMonth> selected, ZoneId zone) {
		List<InstantRange> windows = new ArrayList<>();
		YearMonth runStart = null;
		YearMonth previous = null;
		for (YearMonth month : new TreeSet<>(selected)) {
			if (runStart == null) {
				runStart = month;
			}
			else if (!month.equals(previous.plusMonths(1))) {
				windows.add(window(runStart, previous, zone));
				runStart = month;
			}
			previous = month;
		}
		if (runStart != null) {
			windows.add(window(runStart, previous, zone));
		}
		return windows;
	}

	private static InstantRange window(YearMonth first, YearMonth last, ZoneId zone) {
		return new InstantRange(first.atDay(1).atStartOfDay(zone).toInstant(),
				last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
	}

	/** Plain 2-dp decimal string (HALF_UP); {@code null} reads as zero. */
	public static String money(BigDecimal amount) {
		return Objects.requireNonNullElse(amount, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

}
