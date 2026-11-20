package com.lms.integrationmanagement.api;

/**
 * Payer details for a hosted checkout page (PAY-GW-01). Any field may be
 * {@code null} when not on record; adapters substitute what their gateway
 * accepts.
 */
public record GatewayPayer(String firstName, String lastName, String email, String phone, String addressLine,
		String city) {

}
