package com.lms.paymentmanagement.order.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * {@code POST /api/v1/orders} request body. Deliberately declares NO {@code
 * price}/{@code amount}/{@code tenantId}/{@code studentId} field at all -
 * not merely ignored, structurally absent - per plan §3/§12: every one of
 * those values is always server-resolved. {@code @JsonIgnoreProperties
 * (ignoreUnknown = true)} additionally guarantees an extra client-supplied
 * field of any name is silently dropped during deserialization rather than
 * causing a parse error, mirroring {@code CourseCreateRequest}'s pattern.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreateRequest(@NotNull UUID courseId) {

}
