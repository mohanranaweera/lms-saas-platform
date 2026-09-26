package com.lms.usermanagement.api;

import java.util.List;
import java.util.UUID;

/**
 * Wave 7 - narrow, read-only teacher lookup for other domains (teacher
 * settlement/finance screens), mirroring {@link StudentLookupApi}'s shape.
 * Returns the teacher's {@code tenant_user} id - the same id {@code
 * course.teacher_id} stores - never the {@code teacher_profile} id. Tenant
 * identity comes from the resolved {@code TenantContext}; no tenant-id
 * parameter. Performs no permission check itself: the calling domain gates
 * its own endpoint.
 */
public interface TeacherLookupApi {

	List<TeacherSummary> listTeachers();

	record TeacherSummary(UUID userId, String name, String email) {

	}

}
