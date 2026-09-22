package com.lms.coursemanagement.course.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure Bean Validation unit coverage for {@link CourseBillingPeriodRequest} - no Spring context,
 * mirroring {@code RefundCreateRequestValidationTest}'s established pattern. Closes the Wave 2
 * review gap: no existing test proves a negative {@code amount} or a malformed {@code currency}
 * is rejected by the DTO's own constraints (as opposed to a business-rule rejection further
 * down in {@code BillingConfigurationService}).
 */
class CourseBillingPeriodRequestValidationTest {

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
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(new BigDecimal("30.00"), "USD",
				Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void aNullEffectiveFromIsValid() {
		// Optional - null means "effective now", resolved server-side.
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(new BigDecimal("30.00"), "USD", null);

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void aNullAmountIsRejected() {
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(null, "USD", Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
	}

	@Test
	void aNegativeAmountIsRejected() {
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(new BigDecimal("-10.00"), "USD",
				Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
	}

	@Test
	void zeroAmountIsAccepted() {
		// A $0 billing period is legitimate (e.g. a promotional period) -
		// distinct from FREE pricing, which is a whole different
		// pricing-model branch resolved by CourseLookupApi.
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(BigDecimal.ZERO, "USD", Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void anAmountWithMoreThanTwoDecimalPlacesIsRejected() {
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(new BigDecimal("30.999"), "USD",
				Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
	}

	@Test
	void aNullCurrencyIsRejected() {
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(new BigDecimal("30.00"), null,
				Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("currency"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "US", "USDD" })
	void aCurrencyThatIsNotExactlyThreeCharactersIsRejected(String invalidCurrency) {
		CourseBillingPeriodRequest request = new CourseBillingPeriodRequest(new BigDecimal("30.00"), invalidCurrency,
				Instant.now());

		Set<ConstraintViolation<CourseBillingPeriodRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("currency"));
	}

}
