package com.lms.usermanagement.teacher.web.dto;

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
 * Pure Bean Validation unit coverage for {@link TeacherCreateRequest} - no
 * Spring context, exercises {@code jakarta.validation} directly the same way
 * {@code @Valid} does at the controller boundary. Mirrors {@code
 * StaffCreateRequestValidationTest}'s structure exactly for the shared
 * name/email/password constraints; unlike {@code StaffCreateRequest}, this
 * DTO has no {@code roleCode} field at all (role is always literally {@code
 * "TEACHER"}, set server-side), so no role-code validation cases exist here.
 */
class TeacherCreateRequestValidationTest {

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
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", "jane@example.test", "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankNameIsRejected(String blankName) {
		TeacherCreateRequest request = new TeacherCreateRequest(blankName, "jane@example.test", "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

	@Test
	void oversizedNameIsRejected() {
		String tooLong = "a".repeat(256);
		TeacherCreateRequest request = new TeacherCreateRequest(tooLong, "jane@example.test", "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

	@Test
	void nameOfExactlyTwoHundredFiftyFiveCharsIsAccepted() {
		String maxLength = "a".repeat(255);
		TeacherCreateRequest request = new TeacherCreateRequest(maxLength, "jane@example.test", "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankEmailIsRejected(String blankEmail) {
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", blankEmail, "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void malformedEmailIsRejected() {
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", "not-an-email", "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void oversizedEmailIsRejected() {
		// A syntactically valid, but over-255-char, email.
		String localPart = "a".repeat(250);
		String tooLong = localPart + "@example.test";
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", tooLong, "password123");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankPasswordIsRejected(String blankPassword) {
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", "jane@example.test", blankPassword);

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void passwordUnderEightCharsIsRejected() {
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", "jane@example.test", "short1");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void passwordOfExactlyEightCharsIsAccepted() {
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", "jane@example.test", "12345678");

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void oversizedPasswordIsRejected() {
		String tooLong = "a".repeat(256);
		TeacherCreateRequest request = new TeacherCreateRequest("Jane Teacher", "jane@example.test", tooLong);

		Set<ConstraintViolation<TeacherCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

}
