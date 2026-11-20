package com.lms.tutemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Unroll a PENDING copy; {@code reason} required (1..500). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelFulfillmentRequest(String reason) {

}
