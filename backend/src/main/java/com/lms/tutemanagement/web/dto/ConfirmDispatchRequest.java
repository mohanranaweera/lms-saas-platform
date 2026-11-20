package com.lms.tutemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/** Confirm dispatch of up to 3000 PENDING postal copies (all or nothing). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmDispatchRequest(List<UUID> fulfillmentIds) {

}
