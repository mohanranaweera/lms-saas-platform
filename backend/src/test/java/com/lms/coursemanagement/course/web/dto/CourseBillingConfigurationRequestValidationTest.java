package com.lms.coursemanagement.course.web.dto;

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

/**
 * Pure Bean Validation unit coverage for {@link CourseBillingConfigurationRequest} - no Spring
 * context, mirroring {@code RefundCreateRequestValidationTest}'s established pattern. Closes the
 * Wave 2 review gap: {@code CourseBillingAndLifecycleIntegrationTest} only ever exercises
 * business-rule (service-layer) rejections (e.g. {@code sessionRate} on a non-{@code SESSION}
 * course), never the DTO's own {@code @DecimalMin}/{@code @Size} constraints.
 */
class CourseBillingConfigurationRequestValidationTest {

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
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(new BigDecimal("15.00"),
				"USD", false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void aNullSessionRateIsValid() {
		// sessionRate is optional at the DTO level - only meaningful/required
		// for SESSION pricing, enforced by BillingConfigurationService, not
		// here.
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(null, "USD", false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void aNegativeSessionRateIsRejected() {
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(new BigDecimal("-0.01"),
				"USD", false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("sessionRate"));
	}

	@Test
	void zeroSessionRateIsAccepted() {
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(BigDecimal.ZERO, "USD",
				false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void aSessionRateWithMoreThanTwoDecimalPlacesIsRejected() {
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(new BigDecimal("15.999"),
				"USD", false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("sessionRate"));
	}

	@Test
	void aNullCurrencyIsRejected() {
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(new BigDecimal("15.00"),
				null, false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("currency"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "US", "USDD", "usd " })
	void aCurrencyThatIsNotExactlyThreeCharactersIsRejected(String invalidCurrency) {
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(new BigDecimal("15.00"),
				invalidCurrency, false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("currency"));
	}

	@Test
	void aThreeCharacterCurrencyIsAccepted() {
		CourseBillingConfigurationRequest request = new CourseBillingConfigurationRequest(new BigDecimal("15.00"),
				"EUR", false);

		Set<ConstraintViolation<CourseBillingConfigurationRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

}
