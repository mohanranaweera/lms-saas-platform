package com.lms.usermanagement.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The narrow, read-only contract other domains depend on to resolve a
 * {@code StudentProfile}'s own resource id (the id every {@code
 * /api/v1/students/{id}/...} URL addresses) into the opaque cross-domain
 * {@code studentId} (= {@code tenant_user.id}) that {@code
 * enrollment-management}/{@code ledger-settlement-management}/{@code
 * attendance-management}/{@code exam-management} actually key their own
 * tables by - added for Wave 3's staff-facing, studentId-scoped cross-domain
 * reads (student Enrollments/Ledger/Attendance/Exams tabs) and the Teacher
 * course roster read. Mirrors {@code coursemanagement.api.CourseLookupApi}'s
 * shape/tenant-context discipline: every method resolves tenant identity
 * exclusively from {@link com.lms.common.tenant.TenantContext}; there is no
 * overload that accepts a caller-supplied tenant id.
 */
public interface StudentLookupApi {

	/**
	 * Tenant-scoped: a {@code studentProfileId} belonging to a different
	 * tenant resolves to {@link Optional#empty()}, exactly like every other
	 * tenant-scoped lookup in this codebase - the caller must treat an empty
	 * result as "not found" (404), never distinguish it from "genuinely does
	 * not exist" (anti-enumeration, per {@code .claude/rules/tenancy.md}).
	 */
	Optional<UUID> resolveUserId(UUID studentProfileId);

	/**
	 * Batch-shaped composition read for a roster/list view (e.g. Teacher
	 * course roster) - keyed by {@code userId} (the id {@link
	 * com.lms.enrollmentmanagement.api.EnrollmentAccessApi
	 * #listCurrentlyEnrolledStudentIds(UUID)} already returns), never by
	 * {@code StudentProfile} id. An id absent from the caller's own tenant is
	 * silently omitted, mirroring {@code UserProvisioningApi
	 * #findTenantUserSummaries}'s documented "never cross-tenant" contract.
	 */
	List<StudentSummary> getStudentSummariesByUserId(Collection<UUID> userIds);

}
