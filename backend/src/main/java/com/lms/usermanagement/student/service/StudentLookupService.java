package com.lms.usermanagement.student.service;

import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.RegistrationReportStudent;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import com.lms.usermanagement.api.StudentSummary;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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

	private static final int INDEX_CHUNK = 1000;

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
	public Optional<StudentContact> findContact(UUID userId) {
		return findContacts(List.of(userId)).stream().findFirst();
	}

	@Override
	public List<StudentContact> findContacts(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		List<StudentProfile> profiles = studentProfileRepository
			.findAll((root, query, cb) -> root.get("userId").in(userIds));
		Map<UUID, TenantUserSummary> summariesByUserId = userProvisioningApi
			.findTenantUserSummaries(profiles.stream().map(StudentProfile::getUserId).toList())
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
		return profiles.stream().map(profile -> {
			TenantUserSummary summary = summariesByUserId.get(profile.getUserId());
			return new StudentContact(profile.getUserId(), profile.getName(),
					(summary != null) ? summary.email() : null, profile.getMobile(), profile.getAddressLine(),
					profile.getCity(), profile.getWhatsappNumber(), (summary != null) ? summary.status() : null);
		}).toList();
	}

	@Override
	public List<StudentContact> findContactsByStudentIds(Collection<UUID> studentProfileIds) {
		if (studentProfileIds == null || studentProfileIds.isEmpty()) {
			return List.of();
		}
		List<UUID> userIds = studentProfileRepository.findAllById(studentProfileIds)
			.stream()
			.map(StudentProfile::getUserId)
			.toList();
		return findContacts(userIds);
	}

	@Override
	public List<UUID> listAllStudentUserIds() {
		return studentProfileRepository.findAll().stream().map(StudentProfile::getUserId).toList();
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

	@Override
	public List<StudentIndexEntry> findStudentIndexEntries(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(userIds));
		List<StudentIndexEntry> result = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += INDEX_CHUNK) {
			List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + INDEX_CHUNK));
			studentProfileRepository.findAll((root, query, cb) -> root.get("userId").in(chunk))
				.forEach(profile -> result.add(
						new StudentIndexEntry(profile.getUserId(), profile.getName(), profile.getIndexNumber())));
		}
		return result;
	}

	@Override
	public List<UUID> findRegistrationOperatorIds() {
		return studentProfileRepository.findAttributeValues((root, query, cb) -> cb.isNotNull(root.get("createdBy")),
				"createdBy", UUID.class).stream().distinct().toList();
	}

	@Override
	public List<RegistrationReportStudent> findRegistrationsBetween(java.time.Instant fromInclusive,
			java.time.Instant toExclusive, int maxRows) {
		if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) return List.of();
		List<StudentProfile> profiles = studentProfileRepository.findAll((root, query, cb) -> {
			query.orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
			return cb.and(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive),
					cb.lessThan(root.get("createdAt"), toExclusive));
		});
		List<UUID> userIds = profiles.stream().limit(Math.max(1L, (long) maxRows + 1))
			.map(StudentProfile::getUserId).toList();
		Map<UUID, TenantUserSummary> summaries = userProvisioningApi.findTenantUserSummaries(userIds).stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
		return profiles.stream().limit(Math.max(1L, (long) maxRows + 1)).map(profile -> {
			TenantUserSummary summary = summaries.get(profile.getUserId());
			return new RegistrationReportStudent(profile.getId(), profile.getUserId(), profile.getIndexNumber(),
					profile.getName(), summary == null ? null : summary.email(), profile.getMobile(), profile.getCreatedAt(),
					profile.getCreatedBy(), profile.getRegistrationStatus() == null ? null : profile.getRegistrationStatus().name());
		}).toList();
	}

	@Override
	public List<UnpaidStudentProfile> findUnpaidStudentProfiles(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(userIds));
		List<UnpaidStudentProfile> result = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += INDEX_CHUNK) {
			List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + INDEX_CHUNK));
			List<StudentProfile> profiles = studentProfileRepository
				.findAll((root, query, cb) -> root.get("userId").in(chunk));
			Map<UUID, TenantUserSummary> summariesByUserId = userProvisioningApi
				.findTenantUserSummaries(profiles.stream().map(StudentProfile::getUserId).toList())
				.stream()
				.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
			profiles.forEach(profile -> {
				TenantUserSummary summary = summariesByUserId.get(profile.getUserId());
				result.add(new UnpaidStudentProfile(profile.getId(), profile.getUserId(), profile.getIndexNumber(),
						profile.getName(), summary == null ? null : summary.email(), profile.getMobile()));
			});
		}
		return result;
	}

}
