package com.lms.tutemanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.tutemanagement.domain.TutePaymentType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TUTE-01 section 3 field rules - pure, so every rule is unit-testable. Session eligibility
 * (the lesson is a PAID session of the same course) needs course data and is checked by the
 * service afterwards.
 */
public final class TuteInputValidator {

	public static final int NAME_MAX = 200;

	public static final int DESCRIPTION_MAX = 2000;

	public static final int QUANTITY_MAX = 1_000_000;

	public static final BigDecimal PRICE_MAX = new BigDecimal("999999.99");

	private TuteInputValidator() {
	}

	/** Raw input as received (strings / decimals so wrong types become field errors, not 500s). */
	public record TuteInput(String name, BigDecimal quantity, UUID paidLessonId, String paymentType, BigDecimal price,
			String description) {
	}

	public record ValidTute(String name, int quantity, TutePaymentType paymentType, BigDecimal price,
			UUID paidLessonId, String description) {
	}

	public static ValidTute validate(TuteInput input) {
		if (input == null) {
			throw invalid(List.of(new FieldError("name", "Tute name is required")));
		}
		List<FieldError> errors = new ArrayList<>();
		String name = input.name() == null ? "" : input.name().strip();
		if (name.isEmpty()) {
			errors.add(new FieldError("name", "Tute name is required"));
		}
		else if (name.length() > NAME_MAX) {
			errors.add(new FieldError("name", "Tute name must be at most " + NAME_MAX + " characters"));
		}
		Integer quantity = null;
		if (input.quantity() == null) {
			errors.add(new FieldError("quantity", "Quantity is required"));
		}
		else if (input.quantity().signum() < 0 || input.quantity().stripTrailingZeros().scale() > 0
				|| input.quantity().compareTo(BigDecimal.valueOf(QUANTITY_MAX)) > 0) {
			errors.add(new FieldError("quantity", "Quantity must be a whole number from 0 to 1,000,000"));
		}
		else {
			quantity = input.quantity().intValueExact();
		}
		TutePaymentType paymentType = parseType(input.paymentType());
		if (paymentType == null) {
			errors.add(new FieldError("paymentType", "Choose Covered by class fees, Paid or Free"));
		}
		String description = input.description() == null || input.description().isBlank() ? null
				: input.description().strip();
		if (description != null && description.length() > DESCRIPTION_MAX) {
			errors.add(new FieldError("description", "Description must be at most " + DESCRIPTION_MAX + " characters"));
		}
		BigDecimal price = null;
		if (paymentType == TutePaymentType.PAID) {
			if (input.price() == null) {
				errors.add(new FieldError("price", "Price is required for a Paid tute"));
			}
			else if (input.price().signum() <= 0 || input.price().compareTo(PRICE_MAX) > 0
					|| input.price().stripTrailingZeros().scale() > 2) {
				errors.add(new FieldError("price", "Price must be more than 0 and at most 999,999.99 (2 decimals)"));
			}
			else {
				price = input.price().setScale(2);
			}
		}
		if (!errors.isEmpty()) {
			throw invalid(errors);
		}
		if (paymentType != TutePaymentType.PAID && input.price() != null) {
			throw new FieldValidationException(ApiErrorCodes.TUTE_PRICE_NOT_ALLOWED,
					"Only Paid tutes have a price",
					List.of(new FieldError("price", "Remove the price for a tute that is not Paid")));
		}
		if (paymentType != TutePaymentType.PAID && input.paidLessonId() != null) {
			throw sessionNotEligible("A paid session can only be linked to a Paid tute");
		}
		return new ValidTute(name, quantity, paymentType, price, input.paidLessonId(), description);
	}

	static FieldValidationException sessionNotEligible(String message) {
		return new FieldValidationException(ApiErrorCodes.TUTE_SESSION_NOT_ELIGIBLE, message,
				List.of(new FieldError("paidLessonId", message)));
	}

	private static TutePaymentType parseType(String value) {
		if (value == null) {
			return null;
		}
		try {
			return TutePaymentType.valueOf(value.strip());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static FieldValidationException invalid(List<FieldError> errors) {
		return new FieldValidationException("The tute is not valid", errors);
	}

}
