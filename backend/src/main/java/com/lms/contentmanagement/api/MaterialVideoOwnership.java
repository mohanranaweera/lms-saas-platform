package com.lms.contentmanagement.api;

import java.util.UUID;

/**
 * Resolved course-ownership/entitlement context for a {@code video_asset},
 * derived from the {@code material} row that references it - mirrors
 * {@code coursemanagement.api.LessonOwnership}'s exact shape/purpose, for the
 * same reason: {@code video-access-management} must never import
 * {@code content-management}'s {@code domain}/{@code repository} packages,
 * per {@code .claude/rules/architecture.md}, so this narrow read-only record
 * is the only thing {@code VideoAccessGuard} is permitted to depend on.
 *
 * @param materialId the {@code material} row that references this video asset.
 * @param courseId the course the owning material's lesson belongs to.
 * @param teacherId the course's assigned teacher id.
 * @param coursePublished whether the course is currently {@code PUBLIC}.
 */
public record MaterialVideoOwnership(UUID materialId, UUID courseId, UUID teacherId, boolean coursePublished) {

}
