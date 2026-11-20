package com.lms.usermanagement.student.service;

/** A student's own billing address (V58); either field may be {@code null} until set. */
public record StudentBillingAddress(String addressLine, String city) {

}
