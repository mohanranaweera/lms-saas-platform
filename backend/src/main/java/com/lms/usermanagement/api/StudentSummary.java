package com.lms.usermanagement.api;

import java.util.UUID;

/**
 * Minimal cross-module student display projection - {@code studentProfileId}
 * is the resource id every {@code /api/v1/students/{id}} URL addresses;
 * {@code userId} is the opaque cross-domain id other modules key their own
 * {@code studentId} columns by. Never the {@code StudentProfile} JPA entity.
 */
public record StudentSummary(UUID studentProfileId, UUID userId, String name, String email) {

}
