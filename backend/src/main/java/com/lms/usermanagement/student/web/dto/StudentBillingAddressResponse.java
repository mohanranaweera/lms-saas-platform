package com.lms.usermanagement.student.web.dto;

/** A student's own billing address; both fields are {@code null} until the student sets them. */
public record StudentBillingAddressResponse(String addressLine, String city) {

}
