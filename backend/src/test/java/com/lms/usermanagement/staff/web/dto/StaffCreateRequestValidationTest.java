package com.lms.usermanagement.staff.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure Bean Validation unit coverage for {@link StaffCreateRequest} - no
 * Spring context, exercises {@code jakarta.validation} directly the same way
 * {@code @Valid} does at the controller boundary. The {@code roleCode}
 * {@code @Pattern} is deliberately given its own targeted rejection cases
 * (not just a generic invalid string), since excluding
 * {@code TENANT_ADMIN}/{@code TEACHER}/{@code TEACHER_ASSISTANT}/{@code
 * STUDENT} from staff-assignable roles is the most security-relevant part of
 * this DTO's contract.
 */
class StaffCreateRequestValidationTest {

	private static ValidatorFactory validatorFactory;

	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@Test
	void aFullyValidRequestHasNoViolations() {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", "password123",
				"FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankNameIsRejected(String blankName) {
		StaffCreateRequest request = new StaffCreateRequest(blankName, "jane@example.test", "password123",
				"FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankEmailIsRejected(String blankEmail) {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", blankEmail, "password123", "FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void malformedEmailIsRejected() {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "not-an-email", "password123",
				"FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankPasswordIsRejected(String blankPassword) {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", blankPassword,
				"FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void passwordUnderEightCharsIsRejected() {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", "short1",
				"FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void passwordOfExactlyEightCharsIsAccepted() {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", "12345678",
				"FINANCE_STAFF");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "FINANCE_STAFF", "COURSE_COORDINATOR", "STUDENT_SUPPORT", "CONTENT_MANAGER",
			"EXAM_MANAGER", "ATTENDANCE_OPERATOR", "READ_ONLY_AUDITOR" })
	void eachOfTheSevenAssignableStaffRoleCodesIsAccepted(String roleCode) {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", "password123",
				roleCode);

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void tenantAdminRoleCodeIsSpecificallyRejectedAsTheMostSecurityRelevantCase() {
		// TENANT_ADMIN is a real Role enum value elsewhere, but must never be
		// assignable through Staff Management - this is the single most
		// important rejection case for this @Pattern, not just a generic
		// invalid-string case.
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", "password123",
				"TENANT_ADMIN");

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("roleCode"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "STUDENT", "TEACHER", "TEACHER_ASSISTANT", "not_a_real_role", "" })
	void everyOtherOutOfPatternRoleCodeIsRejected(String roleCode) {
		StaffCreateRequest request = new StaffCreateRequest("Jane Staff", "jane@example.test", "password123",
				roleCode);

		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("roleCode"));
	}

}
