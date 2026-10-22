package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lms.common.api.FieldError;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.web.dto.TeacherIncomeRunRequest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** RPT-01 filter validation (spec section 3): every failure is a 422 with field errors. */
class TeacherIncomeRequestValidatorTest {

	private static final UUID TEACHER = UUID.randomUUID();

	private static final UUID STUDENT = UUID.randomUUID();

	private TeacherIncomeRequestValidator validator;

	@BeforeEach
	void setUp() {
		UserProvisioningApi users = mock(UserProvisioningApi.class);
		// Tenant-scoped lookup: a foreign-tenant id is simply absent.
		when(users.findTenantUserSummaries(anyCollection()))
			.thenReturn(List.of(new TenantUserSummary(TEACHER, "t@example.test", "TEACHER", "active"),
					new TenantUserSummary(STUDENT, "s@example.test", "STUDENT", "active")));
		validator = new TeacherIncomeRequestValidator(users);
	}

	private static List<String> fields(Throwable error) {
		assertThat(error).isInstanceOf(ReportValidationException.class);
		ReportValidationException ex = (ReportValidationException) error;
		assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
		return ex.getFieldErrors().stream().map(FieldError::field).toList();
	}

	private List<String> rejected(TeacherIncomeRunRequest request) {
		Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> validator.validate(request));
		return fields(error);
	}

	@Test
	void acceptsAValidMonthsAndDatesFilter() {
		TeacherIncomeCriteria months = validator.validate(new TeacherIncomeRunRequest(List.of(TEACHER, TEACHER),
				"MANUAL_RECEIPT", "MONTHS", List.of("2025-01", "2024-12"), null, null));
		assertThat(months.teacherIds()).containsExactly(TEACHER);
		assertThat(months.paymentMethod()).isEqualTo(PaymentMethod.MANUAL_RECEIPT);
		assertThat(months.periodMode()).isEqualTo(PeriodMode.MONTHS);
		assertThat(months.months()).containsExactly(YearMonth.of(2024, 12), YearMonth.of(2025, 1));
		TeacherIncomeCriteria dates = validator.validate(
				new TeacherIncomeRunRequest(List.of(TEACHER), "", "DATES", null, "2025-01-01", "2026-01-01"));
		assertThat(dates.startDate()).isEqualTo(LocalDate.of(2025, 1, 1));
		assertThat(dates.paymentMethod()).isNull();
	}

	@Test
	void teachersAreRequiredBoundedAndMustBeTeachersOfThisTenant() {
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(), null, "MONTHS", List.of("2025-01"), null, null)))
			.containsExactly("teacherIds");
		List<UUID> many = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			many.add(UUID.randomUUID());
		}
		assertThat(rejected(new TeacherIncomeRunRequest(many, null, "MONTHS", List.of("2025-01"), null, null)))
			.containsExactly("teacherIds");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER, UUID.randomUUID(), STUDENT), null, "MONTHS",
				List.of("2025-01"), null, null)))
			.containsExactly("teacherIds[1]", "teacherIds[2]");
	}

	@Test
	void periodModeIsRequiredAndOnlyTheActiveModesFieldsAreAllowed() {
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, null, null, null, null)))
			.containsExactly("periodMode");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "WEEKS", null, null, null)))
			.containsExactly("periodMode");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "MONTHS", List.of("2025-01"),
				"2025-01-01", "2025-01-31")))
			.containsExactly("startDate", "endDate");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "DATES", List.of("2025-01"),
				"2025-01-01", "2025-01-31")))
			.containsExactly("months");
	}

	@Test
	void monthsMustBeValidAndAtMost36() {
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "MONTHS", List.of(), null, null)))
			.containsExactly("months");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "MONTHS",
				List.of("2025-13", "2025-1", "25-01", "abc"), null, null)))
			.containsExactly("months[0]", "months[1]", "months[2]", "months[3]");
		List<String> months = new ArrayList<>();
		YearMonth month = YearMonth.of(2020, 1);
		for (int i = 0; i < 37; i++) {
			months.add(month.plusMonths(i).toString());
		}
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "MONTHS", months, null, null)))
			.containsExactly("months");
		assertThat(validator.validate(new TeacherIncomeRunRequest(List.of(TEACHER), null, "MONTHS",
				months.subList(0, 36), null, null)).months()).hasSize(36);
	}

	@Test
	void datesMustBeOrderedAndSpanAtMost366Days() {
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "DATES", null, null, "2025-01-01")))
			.containsExactly("startDate");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "DATES", null, "2025-02-30",
				"2025-03-01")))
			.containsExactly("startDate");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "DATES", null, "2025-03-02",
				"2025-03-01")))
			.containsExactly("startDate");
		assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), null, "DATES", null, "2024-01-01",
				"2025-01-01")))
			.containsExactly("endDate");
	}

	@Test
	void paymentMethodMustBeACashMethod() {
		for (String bad : List.of("FREE", "STAFF_GRANTED", "gateway", "CARD")) {
			assertThat(rejected(new TeacherIncomeRunRequest(List.of(TEACHER), bad, "MONTHS", List.of("2025-01"), null,
					null)))
				.as(bad)
				.containsExactly("paymentMethod");
		}
		assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(ReportValidationException.class);
	}

}
