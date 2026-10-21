package com.lms.paymentmanagement.slip.service;

/**
 * V90: the delivery address snapshot of the order a slip pays for (set only
 * when the student selected postage), so the reviewer can fulfil the
 * postage. {@code null} as a whole when the order has no delivery snapshot.
 */
public record PaymentSlipDelivery(String recipientName, String addressLine, String city, String phone) {

}
