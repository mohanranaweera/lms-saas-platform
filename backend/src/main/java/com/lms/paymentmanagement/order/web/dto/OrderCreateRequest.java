package com.lms.paymentmanagement.order.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /api/v1/orders} request body. Deliberately declares NO {@code
 * price}/{@code tenantId}/{@code studentId} field at all - not merely
 * ignored, structurally absent - per plan §3/§12: every one of those values
 * is always server-resolved. {@code @JsonIgnoreProperties(ignoreUnknown =
 * true)} additionally guarantees an extra client-supplied field of any name
 * is silently dropped during deserialization rather than causing a parse
 * error, mirroring {@code CourseCreateRequest}'s pattern.
 *
 * <p>{@code customAmount} (Wave 2) is the one deliberate exception to "amount
 * is always server-resolved" - it is honored ONLY when {@code
 * OrderService#createOrder} resolves the course's pricing model as {@code
 * CUSTOM} AND the caller independently holds staff {@code
 * DomainArea.COURSES}/{@code CREATE_EDIT}-equivalent permission; a
 * student-role caller's value here is always rejected outright (400), never
 * silently ignored - see {@code OrderService#createOrder}'s javadoc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreateRequest(@NotNull UUID courseId,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal customAmount) {

}
