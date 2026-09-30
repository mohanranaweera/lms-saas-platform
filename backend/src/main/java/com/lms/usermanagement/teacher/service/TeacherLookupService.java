package com.lms.usermanagement.teacher.service;

import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link TeacherLookupApi} (Wave 7). {@code findAll} is
 * tenant-scoped by {@code TenantAwareRepositoryImpl}, so another tenant's
 * teachers are structurally absent.
 */
@Service
@Transactional(readOnly = true)
public class TeacherLookupService implements TeacherLookupApi {

	private final TeacherProfileRepository teacherProfileRepository;

	private final UserProvisioningApi userProvisioningApi;

	public TeacherLookupService(TeacherProfileRepository teacherProfileRepository,
			UserProvisioningApi userProvisioningApi) {
		this.teacherProfileRepository = teacherProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
	}

	@Override
	public List<TeacherSummary> listTeachers() {
		return summaries(teacherProfileRepository.findAll(), summary -> true);
	}

	@Override
	public List<TeacherSummary> listApprovedActiveTeachers() {
		List<TeacherProfile> approved = teacherProfileRepository.findAll()
			.stream()
			.filter(profile -> profile.getApprovalStatus() == ApprovalStatus.APPROVED)
			.toList();
		return summaries(approved, summary -> "active".equalsIgnoreCase(summary.status()));
	}

	private List<TeacherSummary> summaries(List<TeacherProfile> profiles, Predicate<TenantUserSummary> userFilter) {
		if (profiles.isEmpty()) {
			return List.of();
		}
		Map<java.util.UUID, String> emails = userProvisioningApi
			.findTenantUserSummaries(profiles.stream().map(TeacherProfile::getUserId).toList())
			.stream()
			.filter(userFilter)
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
		return profiles.stream()
			.filter(profile -> emails.containsKey(profile.getUserId()))
			.map(profile -> new TeacherSummary(profile.getUserId(), profile.getName(),
					emails.get(profile.getUserId())))
			.sorted(Comparator.comparing(TeacherSummary::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

}
