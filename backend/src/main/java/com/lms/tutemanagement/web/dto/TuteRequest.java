package com.lms.tutemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Create / edit tute body (TUTE-01 section 3). {@code version} is required on edit (optimistic lock) and ignored on create; {@code quantity} and {@code price} are decimals so a non-integer quantity is a field error. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TuteRequest(String name, BigDecimal quantity, UUID paidLessonId, String paymentType, BigDecimal price, String description, Long version) {

}
