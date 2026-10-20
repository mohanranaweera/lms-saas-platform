package com.lms.paymentmanagement.checkout.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * KLASS-T4c (plan §4.4): a composer item the server will not sell. 422 with one
 * of {@code ITEM_NOT_ELIGIBLE}, {@code ITEM_ALREADY_PAID}, {@code ITEM_PENDING},
 * {@code EMPTY_SELECTION} for quote / order creation; 409 with {@code
 * ITEM_ALREADY_PAID} / {@code ITEM_PENDING} / {@code ORDER_PAYMENT_IN_PROGRESS}
 * when an existing line order can no longer be paid (payment initiation / slip
 * upload), and 409 {@code USE_PAYMENT_COMPOSER} for a legacy MONTHLY order.
 */
public class CheckoutItemException extends ApplicationException {

	public static final String ITEM_NOT_ELIGIBLE = "ITEM_NOT_ELIGIBLE";

	public static final String ITEM_ALREADY_PAID = "ITEM_ALREADY_PAID";

	public static final String ITEM_PENDING = "ITEM_PENDING";

	public static final String EMPTY_SELECTION = "EMPTY_SELECTION";

	/**
	 * KLASS-T8b (D24): 409 - this line order already has a PENDING payment or
	 * an open (SUBMITTED / UNDER_REVIEW) slip, so a second payment attempt for
	 * it is refused.
	 */
	public static final String ORDER_PAYMENT_IN_PROGRESS = "ORDER_PAYMENT_IN_PROGRESS";

	/**
	 * KLASS-T8b (D23): 409 - a MONTHLY course is no longer sold through the
	 * legacy course-wide {@code POST /api/v1/orders}; use the payment composer.
	 */
	public static final String USE_PAYMENT_COMPOSER = "USE_PAYMENT_COMPOSER";

	/** V90: 422 - the cart's items are priced in more than one currency (message names courses + currencies). */
	public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

	/** V90: 422 - postage was requested for a course that has no item in the cart. */
	public static final String POSTAGE_COURSE_NOT_IN_CART = "POSTAGE_COURSE_NOT_IN_CART";

	/** V90: 422 - postage is selected but no usable billing address (address line + city) is on record. */
	public static final String DELIVERY_DETAILS_REQUIRED = "DELIVERY_DETAILS_REQUIRED";

	/** V90: 422 - {@code POST /checkout/orders} without the {@code quoteToken} of a fresh quote. */
	public static final String QUOTE_REQUIRED = "QUOTE_REQUIRED";

	/** V90: 409 - the server re-quote differs from the client's quote; see {@link QuoteChangedException}. */
	public static final String QUOTE_CHANGED = "QUOTE_CHANGED";

	public CheckoutItemException(String errorCode, String message) {
		this(HttpStatus.UNPROCESSABLE_CONTENT, errorCode, message);
	}

	public CheckoutItemException(HttpStatus status, String errorCode, String message) {
		super(status, errorCode, message);
	}

}
