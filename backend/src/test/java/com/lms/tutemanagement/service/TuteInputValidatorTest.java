package com.lms.tutemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.FieldValidationException;
import com.lms.tutemanagement.domain.TutePaymentType;
import com.lms.tutemanagement.service.TuteInputValidator.TuteInput;
import com.lms.tutemanagement.service.TuteInputValidator.ValidTute;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TUTE-01 section 3 field rules: name, quantity, price per payment type, session link per type. */
class TuteInputValidatorTest {

	private static TuteInput input(String name, String quantity, UUID lesson, String type, String price) {
		return new TuteInput(name, quantity == null ? null : new BigDecimal(quantity), lesson, type,
				price == null ? null : new BigDecimal(price), null);
	}

	private static String code(Runnable call) {
		try {
			call.run();
		}
		catch (FieldValidationException ex) {
			return ex.getErrorCode() + ":" + ex.getFieldErrors().get(0).field();
		}
		return "OK";
	}

	@Test
	void validInputsAreNormalized() {
		ValidTute paid = TuteInputValidator.validate(input("  Unit 1 notes ", "10", UUID.randomUUID(), "PAID", "250.5"));
		assertThat(paid.name()).isEqualTo("Unit 1 notes");
		assertThat(paid.quantity()).isEqualTo(10);
		assertThat(paid.paymentType()).isEqualTo(TutePaymentType.PAID);
		assertThat(paid.price()).isEqualByComparingTo("250.50");
		ValidTute covered = TuteInputValidator.validate(input("Notes", "0", null, "COVERED_BY_CLASS_FEES", null));
		assertThat(covered.quantity()).isZero();
		assertThat(covered.price()).isNull();
		assertThat(TuteInputValidator.validate(input("Notes", "1000000", null, "FREE", null)).quantity())
			.isEqualTo(1_000_000);
	}

	@Test
	void nameAndQuantityRules() {
		assertThat(code(() -> TuteInputValidator.validate(input("  ", "1", null, "FREE", null))))
			.isEqualTo("VALIDATION_ERROR:name");
		assertThat(code(() -> TuteInputValidator.validate(input("x".repeat(201), "1", null, "FREE", null))))
			.isEqualTo("VALIDATION_ERROR:name");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", null, null, "FREE", null))))
			.isEqualTo("VALIDATION_ERROR:quantity");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "-1", null, "FREE", null))))
			.isEqualTo("VALIDATION_ERROR:quantity");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1.5", null, "FREE", null))))
			.isEqualTo("VALIDATION_ERROR:quantity");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1000001", null, "FREE", null))))
			.isEqualTo("VALIDATION_ERROR:quantity");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "SOMETHING", null))))
			.isEqualTo("VALIDATION_ERROR:paymentType");
		assertThat(code(() -> TuteInputValidator
			.validate(new TuteInput("Notes", BigDecimal.ONE, null, "FREE", null, "d".repeat(2001)))))
			.isEqualTo("VALIDATION_ERROR:description");
	}

	@Test
	void priceIsRequiredOnlyForPaidAndRejectedOtherwise() {
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "PAID", null))))
			.isEqualTo("VALIDATION_ERROR:price");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "PAID", "0"))))
			.isEqualTo("VALIDATION_ERROR:price");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "PAID", "1000000"))))
			.isEqualTo("VALIDATION_ERROR:price");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "PAID", "1.234"))))
			.isEqualTo("VALIDATION_ERROR:price");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "PAID", "999999.99"))))
			.isEqualTo("OK");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "FREE", "10"))))
			.isEqualTo("TUTE_PRICE_NOT_ALLOWED:price");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", null, "COVERED_BY_CLASS_FEES", "0"))))
			.isEqualTo("TUTE_PRICE_NOT_ALLOWED:price");
	}

	@Test
	void sessionLinkOnlyForPaidTutes() {
		UUID lesson = UUID.randomUUID();
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", lesson, "FREE", null))))
			.isEqualTo("TUTE_SESSION_NOT_ELIGIBLE:paidLessonId");
		assertThat(code(() -> TuteInputValidator.validate(input("Notes", "1", lesson, "COVERED_BY_CLASS_FEES", null))))
			.isEqualTo("TUTE_SESSION_NOT_ELIGIBLE:paidLessonId");
		assertThat(TuteInputValidator.validate(input("Notes", "1", lesson, "PAID", "100")).paidLessonId())
			.isEqualTo(lesson);
		assertThatThrownBy(() -> TuteInputValidator.validate(null)).isInstanceOf(FieldValidationException.class);
	}

}
