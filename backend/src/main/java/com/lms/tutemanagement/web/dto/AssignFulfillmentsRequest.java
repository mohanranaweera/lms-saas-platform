package com.lms.tutemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/** Manual assignment: up to 200 course participants; {@code method} POSTAL (default) or COLLECTION. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignFulfillmentsRequest(List<UUID> studentIds, String method) {

}
