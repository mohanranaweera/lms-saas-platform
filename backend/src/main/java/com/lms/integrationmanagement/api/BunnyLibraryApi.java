package com.lms.integrationmanagement.api;

import java.util.UUID;

/**
 * A teacher's own Bunny Stream library inside the tenant's Bunny account
 * (MEDIA-INT-01A / ADR-018 Amendment 1), for other domains - MEDIA-INT-03
 * calls this before a teacher's first upload. Tenant comes from the request's
 * {@code TenantContext}; no method accepts a tenant id. Never exposes keys or
 * the Bunny library id.
 */
public interface BunnyLibraryApi {

	/**
	 * Returns the teacher's library, creating it in Bunny first if the teacher
	 * has none. The caller must be that teacher, or hold {@code INTEGRATIONS}
	 * CREATE_EDIT.
	 * @throws com.lms.common.error.ConflictException no ACTIVE Bunny account, or
	 * the library is being set up by another request
	 * @throws com.lms.common.error.NotFoundException not a teacher of this tenant
	 */
	TeacherLibrarySummary ensureTeacherLibrary(UUID teacherUserId);

	/** @param status {@code ACTIVE} on success */
	record TeacherLibrarySummary(UUID id, String status, String libraryName) {

	}

}
