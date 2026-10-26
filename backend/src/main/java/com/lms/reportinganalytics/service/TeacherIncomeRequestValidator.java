package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.service.support.ReportPeriodRules;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Period;
import com.lms.reportinganalytics.web.dto.TeacherIncomeRunRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of the RPT-01 filter (spec section 3 limits). Every
 * failure is a {@code 422} {@link ReportValidationException} carrying one field
 * error per problem. Teacher ids are resolved through the tenant-scoped {@link
 * UserProvisioningApi}, so an id from another tenant is indistinguishable from
 * an unknown id (same message - nothing about the other tenant leaks). The
 * period and payment-method rules are the shared {@link ReportPeriodRules}.
 */
@Component
public class TeacherIncomeRequestValidator {

	static final int MAX_TEACHERS = 50;

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
		PaymentMethod method = ReportPeriodRules.parseMethod(request.paymentMethod(), errors);
		Period period = ReportPeriodRules.parsePeriod(request.periodMode(), request.months(), request.startDate(),
				request.endDate(), errors);
		if (errors.isEmpty() && teacherIds != null) {
			verifyTeachersBelongToTenant(teacherIds, errors);
		}
		if (!errors.isEmpty()) {
			throw new ReportValidationException(errors);
		}
		return new TeacherIncomeCriteria(List.copyOf(teacherIds), method, PeriodMode.valueOf(period.mode().name()),
				period.months(), period.startDate(), period.endDate());
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

}
