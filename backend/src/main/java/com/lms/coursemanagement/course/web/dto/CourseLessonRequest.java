package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shared create/edit request shape for a course lesson ("Session").
 * KLASS-T4a: {@code sequence} optional; {@code startsAt}/{@code endsAt} are
 * ISO-8601 instants (the UI converts tenant-timezone date+time);
 * {@code paymentType} defaults to COVERED_BY_CLASS_FEES; {@code price} only
 * for PAID. {@code billingYear}/{@code billingMonth} stay in the request for
 * wire compatibility but are ignored; the service derives Monthly covered
 * sessions from {@code startsAt} in the tenant timezone. On edit every other
 * session field is replaced. Business rule failures return 400 with
 * codes ENDS_BEFORE_START, PRICE_REQUIRED_FOR_PAID, PRICE_NOT_ALLOWED,
 * RESOURCE_TYPE_UNSUPPORTED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseLessonRequest(@NotBlank @Size(max = 255) String title, @Positive Integer sequence,
		Instant startsAt, Instant endsAt, LessonResourceType resourceType, LessonPaymentType paymentType,
		@Digits(integer = 10, fraction = 2) BigDecimal price, @Size(max = 10000) String description,
		@Size(max = 255) String lessonLabel, Integer billingYear,
		Integer billingMonth) {

	/** Pre-KLASS shape. */
	public CourseLessonRequest(String title, Integer sequence) {
		this(title, sequence, null, null, null, null, null, null, null, null, null);
	}

}
