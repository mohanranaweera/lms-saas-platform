package com.lms.usermanagement.api;

import java.util.UUID;

/**
 * A student's contact details for payer-facing flows (PAY-GW-01 hosted
 * checkout). Any of {@code email}/{@code mobile}/{@code addressLine}/{@code
 * city} may be {@code null} when not on record.
 */
public record StudentContact(UUID userId, String name, String email, String mobile, String addressLine,
		String city) {

}
