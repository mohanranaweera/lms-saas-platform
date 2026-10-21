package com.lms.usermanagement.api;

import java.util.UUID;

/**
 * A student's contact details for payer-facing flows (PAY-GW-01 hosted
 * checkout). Any of {@code email}/{@code mobile}/{@code addressLine}/{@code
 * city} may be {@code null} when not on record.
 *
 * <p>Wave 11: also the recipient address source for broadcast SMS/WhatsApp
 * ({@code whatsappNumber} may be {@code null}; WhatsApp then falls back to
 * {@code mobile}). {@code accountStatus} is the {@code tenant_user} status
 * (e.g. {@code active}, {@code suspended}), or {@code null} if unknown.
 */
public record StudentContact(UUID userId, String name, String email, String mobile, String addressLine,
		String city, String whatsappNumber, String accountStatus) {

}
