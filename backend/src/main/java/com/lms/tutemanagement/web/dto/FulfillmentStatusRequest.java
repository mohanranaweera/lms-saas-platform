package com.lms.tutemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code action}: MARK_HANDED_OVER, CHANGE_METHOD (with {@code method}) or RELEASE_HOLD (with {@code reason}, 1..500). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FulfillmentStatusRequest(String action, String method, String reason) {

}
