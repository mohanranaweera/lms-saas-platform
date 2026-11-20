package com.lms.usermanagement.staff.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Bean Validation coverage for the STAFF-MGMT-02 "Add Staff" body: five required fields and no
 * password field at all. Role restriction (Operator/Manager only) is a service rule, covered by
 * {@code StaffManagementIntegrationTest}.
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

	private static StaffCreateRequest valid() {
		return new StaffCreateRequest("Jane", "Staff", "jane@example.test", "+94 77 123 4567", "OPERATOR");
	}

	@Test
	void aFullyValidRequestHasNoViolations() {
		assertThat(validator.validate(valid())).isEmpty();
	}

	@Test
	void theRequestHasNoPasswordField() {
		assertThat(Arrays.stream(StaffCreateRequest.class.getRecordComponents()).map(c -> c.getName()))
			.containsExactly("firstName", "lastName", "email", "mobileNumber", "roleCode")
			.noneMatch(name -> name.toLowerCase().contains("password"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void everyFieldIsRequired(String blank) {
		assertThat(violations(new StaffCreateRequest(blank, "Staff", "jane@example.test", "0771234567", "OPERATOR")))
			.contains("firstName");
		assertThat(violations(new StaffCreateRequest("Jane", blank, "jane@example.test", "0771234567", "OPERATOR")))
			.contains("lastName");
		assertThat(violations(new StaffCreateRequest("Jane", "Staff", blank, "0771234567", "OPERATOR")))
			.contains("email");
		assertThat(violations(new StaffCreateRequest("Jane", "Staff", "jane@example.test", blank, "OPERATOR")))
			.contains("mobileNumber");
		assertThat(violations(new StaffCreateRequest("Jane", "Staff", "jane@example.test", "0771234567", blank)))
			.contains("roleCode");
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-an-email", "a@", "@b.test" })
	void malformedEmailIsRejected(String email) {
		assertThat(violations(new StaffCreateRequest("Jane", "Staff", email, "0771234567", "OPERATOR")))
			.contains("email");
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "12", "+94-<script>" })
	void malformedMobileIsRejected(String mobile) {
		assertThat(violations(new StaffCreateRequest("Jane", "Staff", "jane@example.test", mobile, "OPERATOR")))
			.contains("mobileNumber");
	}

	private static Set<String> violations(StaffCreateRequest request) {
		Set<ConstraintViolation<StaffCreateRequest>> violations = validator.validate(request);
		return violations.stream()
			.map(v -> v.getPropertyPath().toString())
			.collect(java.util.stream.Collectors.toSet());
	}

}
