package com.lms.usermanagement.teacher.storefront.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * STORE-01 / ADR-037 API contract (plan section 6) request/response records.
 * DTOs only - never JPA entities.
 */
public final class StorefrontDtos {

	private StorefrontDtos() {
	}

	/**
	 * {@code StorefrontAdmin}. {@code assignableCourses} is omitted on the
	 * Teacher's own read-only view ({@code GET /teacher-storefronts/me}).
	 */
	public record StorefrontAdminResponse(UUID id, UUID teacherId, String slug, boolean enabled, String previewUrl,
			String publishedUrl, List<String> previousSlugs, List<StorefrontProductResponse> products,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<AssignableCourseResponse> assignableCourses) {

	}

	public record StorefrontProductResponse(UUID courseId, String courseTitle, String courseSlug, boolean published,
			int sortOrder) {

	}

	public record AssignableCourseResponse(UUID courseId, String title, String slug) {

	}

	/** One row of {@code GET /teacher-storefronts}: {@code storefront} is null when none exists yet. */
	public record TeacherStorefrontRowResponse(UUID teacherId, String teacherName,
			StorefrontAdminResponse storefront) {

	}

	/** {@code PUT /teacher-storefronts/{teacherId}}; {@code enabled} null keeps the current value (off on create). */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StorefrontUpdateRequest(String slug, Boolean enabled) {

	}

	/** {@code PUT /teacher-storefronts/{teacherId}/products} - full replace. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StorefrontProductsRequest(List<StorefrontProductItem> items) {

	}

	/** {@code published} defaults to true and {@code sortOrder} to the item's position. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StorefrontProductItem(UUID courseId, Boolean published, Integer sortOrder) {

	}

	/** {@code GET /public/site-context}. */
	public record SiteContextResponse(String kind, StorefrontPublicResponse storefront) {

	}

	public record StorefrontPublicResponse(String slug, UUID teacherId, String displayName, String photoUrl,
			String bio, String host) {

	}

}
