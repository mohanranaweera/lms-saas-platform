package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.web.dto.TeacherIncomeRunRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of the RPT-01 filter (spec section 3 limits). Every
 * failure is a {@code 422} {@link ReportValidationException} carrying one field
 * error per problem. Teacher ids are resolved through the tenant-scoped {@link
 * UserProvisioningApi}, so an id from another tenant is indistinguishable from
 * an unknown id (same message - nothing about the other tenant leaks).
 */
@Component
public class TeacherIncomeRequestValidator {

	static final int MAX_TEACHERS = 50;

	static final int MAX_MONTHS = 36;

	static final long MAX_SPAN_DAYS = 366;

	static final Set<PaymentMethod> FILTERABLE_METHODS = Set.of(PaymentMethod.GATEWAY, PaymentMethod.MANUAL_SLIP,
			PaymentMethod.MANUAL_RECEIPT);

	private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

	private final UserProvisioningApi userProvisioningApi;

	public TeacherIncomeRequestValidator(UserProvisioningApi userProvisioningApi) {
		this.userProvisioningApi = userProvisioningApi;
	}

	public TeacherIncomeCriteria validate(TeacherIncomeRunRequest request) {
		if (request == null) {
			throw new ReportValidationException(List.of(new FieldError("body", "is required")));
		}
		List<FieldError> errors = new ArrayList<>();
		List<UUID> teacherIds = validateTeachers(request.teacherIds(), errors);
		PaymentMethod method = validateMethod(request.paymentMethod(), errors);
		PeriodMode mode = validateMode(request.periodMode(), errors);
		SortedSet<YearMonth> months = null;
		LocalDate start = null;
		LocalDate end = null;
		if (mode == PeriodMode.MONTHS) {
			if (present(request.startDate())) {
				errors.add(new FieldError("startDate", "must not be set when periodMode is MONTHS"));
			}
			if (present(request.endDate())) {
				errors.add(new FieldError("endDate", "must not be set when periodMode is MONTHS"));
			}
			months = validateMonths(request.months(), errors);
		}
		else if (mode == PeriodMode.DATES) {
			if (request.months() != null && !request.months().isEmpty()) {
				errors.add(new FieldError("months", "must not be set when periodMode is DATES"));
			}
			start = parseDate("startDate", request.startDate(), errors);
			end = parseDate("endDate", request.endDate(), errors);
			if (start != null && end != null) {
				if (start.isAfter(end)) {
					errors.add(new FieldError("startDate", "must be on or before endDate"));
				}
				else if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_SPAN_DAYS) {
					errors.add(new FieldError("endDate", "the period must not exceed " + MAX_SPAN_DAYS + " days"));
				}
			}
		}
		if (errors.isEmpty() && teacherIds != null) {
			verifyTeachersBelongToTenant(teacherIds, errors);
		}
		if (!errors.isEmpty()) {
			throw new ReportValidationException(errors);
		}
		return new TeacherIncomeCriteria(List.copyOf(teacherIds), method, mode,
				months == null ? null : java.util.Collections.unmodifiableSortedSet(months), start, end);
	}

	private static List<UUID> validateTeachers(List<UUID> ids, List<FieldError> errors) {
		if (ids == null || ids.isEmpty()) {
			errors.add(new FieldError("teacherIds", "select at least one teacher"));
			return null;
		}
		if (ids.stream().anyMatch(java.util.Objects::isNull)) {
			errors.add(new FieldError("teacherIds", "must not contain empty values"));
			return null;
		}
		List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids));
		if (distinct.size() > MAX_TEACHERS) {
			errors.add(new FieldError("teacherIds", "at most " + MAX_TEACHERS + " teachers can be selected"));
			return null;
		}
		return distinct;
	}

	private void verifyTeachersBelongToTenant(List<UUID> teacherIds, List<FieldError> errors) {
		Map<UUID, TenantUserSummary> found = userProvisioningApi.findTenantUserSummaries(teacherIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity(), (a, b) -> a));
		for (int i = 0; i < teacherIds.size(); i++) {
			TenantUserSummary summary = found.get(teacherIds.get(i));
			// Status is deliberately not checked: suspended/pending teachers' historical income stays reportable.
			if (summary == null || !"TEACHER".equals(summary.roleCode())) {
				errors.add(new FieldError("teacherIds[" + i + "]", "teacher not found"));
			}
		}
	}

	private static PaymentMethod validateMethod(String value, List<FieldError> errors) {
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

	private static PeriodMode validateMode(String value, List<FieldError> errors) {
		if (!present(value)) {
			errors.add(new FieldError("periodMode", "is required (MONTHS or DATES)"));
			return null;
		}
		try {
			return PeriodMode.valueOf(value.trim());
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError("periodMode", "must be MONTHS or DATES"));
			return null;
		}
	}

	private static SortedSet<YearMonth> validateMonths(List<String> values, List<FieldError> errors) {
		if (values == null || values.isEmpty()) {
			errors.add(new FieldError("months", "select at least one month"));
			return null;
		}
		SortedSet<YearMonth> months = new TreeSet<>();
		for (int i = 0; i < values.size(); i++) {
			String value = values.get(i);
			YearMonth month = parseMonth(value);
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

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
