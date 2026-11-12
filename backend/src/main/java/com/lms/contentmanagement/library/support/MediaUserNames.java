package com.lms.contentmanagement.library.support;

import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi.TeacherSummary;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Display names for media library people (uploaders, folder owners, grantees) on staff-only
 * endpoints: the teacher's name, falling back to the account email for other staff. Tenant
 * scoping is done by the called {@code api} lookups (resolved tenant context, no tenant id).
 */
@Component
public class MediaUserNames {

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	public MediaUserNames(TeacherLookupApi teacherLookupApi, UserProvisioningApi userProvisioningApi) {
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
	}

	public Map<UUID, String> names(Collection<UUID> userIds) {
		Map<UUID, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> userIds.contains(teacher.userId()) && teacher.name() != null
					&& !teacher.name().isBlank())
			.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		List<UUID> missing = userIds.stream().filter(id -> !names.containsKey(id)).distinct().toList();
		if (!missing.isEmpty()) {
			for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(missing)) {
				if (summary.email() != null) {
					names.putIfAbsent(summary.userId(), summary.email());
				}
			}
		}
		return names;
	}

	/**
	 * Approved, active teachers of the tenant - the members a folder may be shared with or
	 * transferred to - as {@code (userId, name)} only (never their email), sorted by name.
	 */
	public List<TeacherSummary> eligibleMembers() {
		return teacherLookupApi.listApprovedActiveTeachers()
			.stream()
			.map(teacher -> new TeacherSummary(teacher.userId(), teacher.name(), null))
			.sorted(Comparator.comparing((TeacherSummary teacher) -> teacher.name() == null ? ""
					: teacher.name().toLowerCase(java.util.Locale.ROOT)).thenComparing(TeacherSummary::userId))
			.toList();
	}

}
