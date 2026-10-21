package com.lms.paymentmanagement.checkout.service;

import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutQuote;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * V90: {@code 409 QUOTE_CHANGED} - the order re-quote (taken under the
 * coverage locks) no longer matches the {@code quoteToken} the client
 * confirmed. Nothing is persisted; the fresh quote is returned in {@code
 * error.details.quote} so the client can show the new breakdown and ask the
 * student to confirm again. The quote only holds the caller's own,
 * server-computed data.
 */
public class QuoteChangedException extends CheckoutItemException {

	private final transient CheckoutQuote quote;

	public QuoteChangedException(CheckoutQuote quote) {
		super(HttpStatus.CONFLICT, QUOTE_CHANGED,
				"Prices or charges changed since your quote - please review the updated total");
		this.quote = quote;
	}

	public CheckoutQuote getQuote() {
		return quote;
	}

	@Override
	public Map<String, Object> getDetails() {
		return Map.of("quote", quote);
	}

}
