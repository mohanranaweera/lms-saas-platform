package com.lms.integrationmanagement.api;

/**
 * The online payment method a tenant currently offers at checkout (PAY-GW-01):
 * provider and mode only - never any credential.
 */
public record OnlinePaymentOption(String provider, String mode) {

}
