package com.lms.usermanagement.student.service;

import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.StudentContact;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.usermanagement.api.StudentDeliveryContact;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.RegistrationReportStudent;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import com.lms.usermanagement.api.StudentSummary;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

	private static final String STUDENT_ROLE = "STUDENT";

	private final StudentProfileRepository studentProfileRepository;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	public StudentLookupService(StudentProfileRepository studentProfileRepository,
			UserProvisioningApi userProvisioningApi, PermissionCheckService permissionCheckService) {
		this.studentProfileRepository = studentProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
	}

	@Override
	public Optional<UUID> resolveUserId(UUID studentProfileId) {
		// findById is tenant-scoped by TenantAwareRepositoryImpl - a
		// cross-tenant studentProfileId is structurally invisible here,
		// resolving to empty, never a cross-tenant id leak.
		// STAFF-MGMT-03: staff resolving a pending student by profile id (payment history, enroll,
		// attendance, ...) need View Pending Students; Teacher/Student/background callers unchanged.
		Optional<StudentProfile> profile = studentProfileRepository.findById(studentProfileId);
		profile.ifPresent(p -> PendingStudentAccess.require(permissionCheckService, p));
		return profile.map(StudentProfile::getUserId);
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
	public List<UUID> searchStudentUserIds(String term, int limit) {
		if (term == null || term.isBlank() || limit <= 0) {
			return List.of();
		}
		String pattern = "%" + term.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
			.replace("_", "\\_") + "%";
		// Name/mobile live on the profile; email on tenant_user (identity-access-
		// service). Both reads are tenant-scoped. An email match counts only for a
		// STUDENT-role user - checked by role, not by profile, so a student whose
		// profile row is missing is still found by email.
		Set<UUID> result = new LinkedHashSet<>(studentProfileRepository.findAll((root, query, cb) -> cb.or(
				cb.like(cb.lower(root.get("name")), pattern, '\\'),
				cb.like(cb.lower(cb.coalesce(root.get("mobile"), "")), pattern, '\\')),
				PageRequest.of(0, limit, Sort.by("name", "id"))).map(StudentProfile::getUserId).getContent());
		List<UUID> emailUserIds = userProvisioningApi.findTenantUserIdsByEmailContaining(term, limit);
		if (!emailUserIds.isEmpty()) {
			userProvisioningApi.findTenantUserSummaries(emailUserIds)
				.stream()
				.filter(summary -> STUDENT_ROLE.equals(summary.roleCode()))
				.forEach(summary -> result.add(summary.userId()));
		}
		return result.stream().limit(limit).toList();
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
	public List<StudentDeliveryContact> findDeliveryContacts(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(userIds));
		List<StudentDeliveryContact> result = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += INDEX_CHUNK) {
			List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + INDEX_CHUNK));
			studentProfileRepository.findAll((root, query, cb) -> root.get("userId").in(chunk))
				.forEach(profile -> result.add(new StudentDeliveryContact(profile.getUserId(), profile.getId(),
						profile.getName(), profile.getIndexNumber(), profile.getAddressLine(),
						profile.getAddressLine2(), profile.getCity(), profile.getPostalCode(), deliveryPhone(profile))));
		}
		return result;
	}

	/** Tute delivery phone 1, else the mobile number; {@code null} when neither is set. */
	private static String deliveryPhone(StudentProfile profile) {
		Object tutePhone = profile.readField(StudentProfileField.TUTE_DELIVERY_PHONE_1);
		if (tutePhone instanceof String phone && !phone.isBlank()) {
			return phone.strip();
		}
		String mobile = profile.getMobile();
		return mobile == null || mobile.isBlank() ? null : mobile.strip();
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
