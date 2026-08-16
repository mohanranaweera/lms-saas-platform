package com.lms.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Shared pagination envelope for any platform-wide paginated REST endpoint,
 * carried inside {@link ApiResponse#data()}. This is the platform's first
 * paginated endpoint (course-management's course listings, MVP-008 review
 * follow-up) - no prior pagination convention existed anywhere in this
 * codebase, so this type is deliberately generic/reusable rather than
 * course-management-specific, so future modules adopting pagination reuse
 * this shape instead of inventing a competing one.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
