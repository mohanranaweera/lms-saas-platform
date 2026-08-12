package com.lms.usermanagement.student.web.dto;

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
 * Pure Bean Validation unit coverage for {@link StudentCreateRequest} - no
 * Spring context, exercises {@code jakarta.validation} directly the same way
 * {@code @Valid} does at the controller boundary. Unlike {@code
 * StaffCreateRequestValidationTest}, there is no {@code roleCode} field to
 * test at all - this DTO deliberately has no such field, since Student
 * Management only ever provisions {@code STUDENT}-role accounts through this
 * endpoint (see the DTO's own javadoc).
 */
class StudentCreateRequestValidationTest {

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
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", "jane@example.test", "password123");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankNameIsRejected(String blankName) {
		StudentCreateRequest request = new StudentCreateRequest(blankName, "jane@example.test", "password123");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

	@Test
	void nameOverMaxLengthIsRejected() {
		StudentCreateRequest request = new StudentCreateRequest("a".repeat(256), "jane@example.test", "password123");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankEmailIsRejected(String blankEmail) {
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", blankEmail, "password123");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void emailOverMaxLengthIsRejected() {
		// "a".repeat(250) + "@t.io" keeps the local-part valid-shaped while
		// pushing the overall address past the 255-char @Size limit, so this
		// exercises the length constraint specifically, not the format one.
		String overLongEmail = "a".repeat(250) + "@t.io";
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", overLongEmail, "password123");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void malformedEmailIsRejected() {
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", "not-an-email", "password123");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("email"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankPasswordIsRejected(String blankPassword) {
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", "jane@example.test", blankPassword);

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void passwordUnderEightCharsIsRejected() {
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", "jane@example.test", "short1");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void passwordOfExactlyEightCharsIsAccepted() {
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", "jane@example.test", "12345678");

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void passwordOverMaxLengthIsRejected() {
		StudentCreateRequest request = new StudentCreateRequest("Jane Student", "jane@example.test", "a".repeat(256));

		Set<ConstraintViolation<StudentCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("password"));
	}

	/**
	 * {@link StudentCreateRequest} has no {@code roleCode}/{@code role} field
	 * at all (unlike {@code StaffCreateRequest}) - confirmed here at the
	 * record-component level, not merely "a value would be ignored", since
	 * there is no field to ignore in the first place.
	 */
	@Test
	void theRecordHasNoRoleCodeOrRoleComponentAtAll() {
		java.lang.reflect.RecordComponent[] components = StudentCreateRequest.class.getRecordComponents();

		assertThat(components).extracting(java.lang.reflect.RecordComponent::getName)
			.doesNotContain("roleCode", "role", "tenantId", "mustChangePassword");
	}

}
