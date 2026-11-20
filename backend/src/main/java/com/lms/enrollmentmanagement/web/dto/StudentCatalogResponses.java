package com.lms.enrollmentmanagement.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** KLASS-T4b response bodies of the free-enrollment / student catalog endpoints (plan §4.2). */
public final class StudentCatalogResponses {

	private StudentCatalogResponses() {
	}

	/** {@code POST /api/v1/enrollments/my/courses/{courseId}}. */
	public record SelfEnrollResponse(UUID courseId, Instant enrolledAt, boolean created) {

	}

	/** @param period {@code "MONTH"} or {@code null}. */
	public record PriceDisplay(BigDecimal amount, String currency, String period) {

	}

	public record TagResponse(UUID id, String name) {

	}

	/** One item of {@code GET /api/v1/enrollments/my/catalog}. */
	public record CatalogCourseResponse(UUID courseId, String title, String teacherName, String coverImageUrl,
			PriceDisplay priceDisplay, String pricingModel, List<TagResponse> tags, boolean enrolled,
			boolean registrationEnabled) {

	}

}
