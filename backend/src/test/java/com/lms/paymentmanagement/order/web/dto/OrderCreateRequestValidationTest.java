package com.lms.paymentmanagement.order.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure Bean Validation unit coverage for {@link OrderCreateRequest} - no
 * Spring context. Also asserts, via reflection, that this record has no
 * client-settable {@code price}/{@code amount}/{@code tenantId}/{@code
 * studentId} component at all - the structural guarantee plan §3/§12
 * requires, not merely "ignored if present".
 */
class OrderCreateRequestValidationTest {

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
		OrderCreateRequest request = new OrderCreateRequest(UUID.randomUUID());

		Set<ConstraintViolation<OrderCreateRequest>> violations = validator.validate(request);

		assertThat(violations).isEmpty();
	}

	@Test
	void nullCourseIdIsRejected() {
		OrderCreateRequest request = new OrderCreateRequest(null);

		Set<ConstraintViolation<OrderCreateRequest>> violations = validator.validate(request);

		assertThat(violations).anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("courseId"));
	}

	@Test
	void theRecordHasNoPriceAmountTenantIdOrStudentIdComponentAtAll() {
		RecordComponent[] components = OrderCreateRequest.class.getRecordComponents();

		assertThat(components).extracting(RecordComponent::getName)
			.containsExactly("courseId")
			.doesNotContain("price", "amount", "tenantId", "studentId", "status");
	}

}
