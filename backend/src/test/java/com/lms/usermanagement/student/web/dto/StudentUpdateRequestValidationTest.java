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
 * Pure Bean Validation unit coverage for {@link StudentUpdateRequest} - no
 * Spring context, mirrors {@code StudentCreateRequestValidationTest}'s
 * pattern. Used by both the staff-facing {@code PATCH /api/v1/students/{id}}
 * and the self-service {@code PATCH /api/v1/students/me} endpoints.
 */
class StudentUpdateRequestValidationTest {

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
		StudentUpdateRequest request = new StudentUpdateRequest("Jane Student");

		Set<ConstraintViolation<StudentUpdateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankNameIsRejected(String blankName) {
		StudentUpdateRequest request = new StudentUpdateRequest(blankName);

		Set<ConstraintViolation<StudentUpdateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

	@Test
	void nameOverMaxLengthIsRejected() {
		StudentUpdateRequest request = new StudentUpdateRequest("a".repeat(256));

		Set<ConstraintViolation<StudentUpdateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("name"));
	}

}
