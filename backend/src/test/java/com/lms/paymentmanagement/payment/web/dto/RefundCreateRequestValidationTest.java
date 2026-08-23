package com.lms.paymentmanagement.payment.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure Bean Validation unit coverage for {@link RefundCreateRequest} - no Spring context. */
class RefundCreateRequestValidationTest {

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
		RefundCreateRequest request = new RefundCreateRequest(new BigDecimal("25.00"), "Course cancelled by student");

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void nullAmountIsRejected() {
		RefundCreateRequest request = new RefundCreateRequest(null, "A reason");

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
	}

	@Test
	void zeroAmountIsRejected() {
		RefundCreateRequest request = new RefundCreateRequest(BigDecimal.ZERO, "A reason");

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
	}

	@Test
	void negativeAmountIsRejected() {
		RefundCreateRequest request = new RefundCreateRequest(new BigDecimal("-5.00"), "A reason");

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
	}

	@Test
	void smallestPositiveAmountIsAccepted() {
		RefundCreateRequest request = new RefundCreateRequest(new BigDecimal("0.01"), "A reason");

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void nullReasonIsRejected() {
		RefundCreateRequest request = new RefundCreateRequest(new BigDecimal("10.00"), null);

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("reason"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankReasonIsRejected(String blankReason) {
		RefundCreateRequest request = new RefundCreateRequest(new BigDecimal("10.00"), blankReason);

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("reason"));
	}

	@Test
	void oversizedReasonIsRejected() {
		RefundCreateRequest request = new RefundCreateRequest(new BigDecimal("10.00"), "a".repeat(1001));

		Set<ConstraintViolation<RefundCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("reason"));
	}

}
