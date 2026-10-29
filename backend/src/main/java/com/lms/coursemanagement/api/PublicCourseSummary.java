package com.lms.coursemanagement.api;

import java.util.UUID;

/** Minimal public course card data for another domain's public storefront projection. */
public record PublicCourseSummary(UUID id, String name, String slug, String category, String coverImageUrl) {
}
