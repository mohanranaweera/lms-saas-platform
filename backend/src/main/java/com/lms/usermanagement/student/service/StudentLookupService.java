package com.lms.usermanagement.student.service;

import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link StudentLookupApi} - the cross-module read other domains
 * use to translate between {@code StudentProfile}'s own resource id and the
 * opaque {@code studentId} (= {@code tenant_user.id}) they key their own
 * tables by (Wave 3). Deliberately a separate class from {@link
 * StudentService} - this is a narrow, read-only, cross-module-facing
 * component, kept distinct from that class's own staff-CRUD/self-service
 * responsibilities (mirrors why {@code EnrollmentAccessApiImpl} is its own
 * class rather than folded into {@code EnrollmentQueryService}).
 */
@Service
@Transactional(readOnly = true)
public class StudentLookupService implements StudentLookupApi {

	private final StudentProfileRepository studentProfileRepository;

	private final UserProvisioningApi userProvisioningApi;

	public StudentLookupService(StudentProfileRepository studentProfileRepository,
			UserProvisioningApi userProvisioningApi) {
		this.studentProfileRepository = studentProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
	}

	@Override
	public Optional<UUID> resolveUserId(UUID studentProfileId) {
		// findById is tenant-scoped by TenantAwareRepositoryImpl - a
		// cross-tenant studentProfileId is structurally invisible here,
		// resolving to empty, never a cross-tenant id leak.
		return studentProfileRepository.findById(studentProfileId).map(StudentProfile::getUserId);
	}

	@Override
	public List<StudentSummary> getStudentSummariesByUserId(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		List<StudentProfile> profiles = studentProfileRepository
			.findAll((root, query, cb) -> root.get("userId").in(userIds));
		Map<UUID, TenantUserSummary> summariesByUserId = userProvisioningApi
			.findTenantUserSummaries(profiles.stream().map(StudentProfile::getUserId).toList())
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
		return profiles.stream()
			.map(profile -> {
				TenantUserSummary summary = summariesByUserId.get(profile.getUserId());
				String email = (summary != null) ? summary.email() : null;
				return new StudentSummary(profile.getId(), profile.getUserId(), profile.getName(), email);
			})
			.toList();
	}

}
