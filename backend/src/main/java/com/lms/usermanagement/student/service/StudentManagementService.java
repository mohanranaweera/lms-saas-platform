package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.RequestRefusedException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.StudentProfileMediaApi;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.CourseParticipationApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentQrLabelApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.domain.StudentProfile.ManagedDetails;
import com.lms.usermanagement.student.domain.StudentQrTokens;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentCreatedResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentDetail;
import com.lms.usermanagement.student.web.dto.StudentRegistrationForm;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STU-MGMT-02 (docs/specs/student-management-parity.md): Klass-parity create / detail /
 * edit / approve / remove and the student QR. Every method re-checks the {@code
 * STUDENTS} permission (defense in depth on top of the controller's
 * {@code @PreAuthorize}); every read goes through the tenant-scoped {@link
 * StudentProfileRepository}, so another tenant's student id is a 404. Removed students
 * are 404 here too. Course selection creates free participations only (ADR-028) -
 * never an order, payment, ledger entry or paid entitlement.
 */
@Service
@Transactional
public class StudentManagementService implements StudentQrLabelApi {

	static final String TARGET = "student_profile";

	private static final LocalDate MIN_DATE_OF_BIRTH = LocalDate.of(1900, 1, 1);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final StudentProfileRepository studentProfileRepository;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	private final CourseLookupApi courseLookupApi;

	private final CourseParticipationApi courseParticipationApi;

	private final StudentProfileMediaApi studentProfileMediaApi;

	private final TenantConfigApi tenantConfigApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public StudentManagementService(StudentProfileRepository studentProfileRepository,
			UserProvisioningApi userProvisioningApi, PermissionCheckService permissionCheckService,
			CourseLookupApi courseLookupApi, CourseParticipationApi courseParticipationApi,
			StudentProfileMediaApi studentProfileMediaApi, TenantConfigApi tenantConfigApi, AuditLogApi auditLogApi,
			TenantContext tenantContext) {
		this.studentProfileRepository = studentProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
		this.courseLookupApi = courseLookupApi;
		this.courseParticipationApi = courseParticipationApi;
		this.studentProfileMediaApi = studentProfileMediaApi;
		this.tenantConfigApi = tenantConfigApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------
	// Create / detail / edit.
	// ------------------------------------------------------------------

	public StudentCreatedResponse create(StudentRegistrationForm form) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		List<FieldError> errors = new ArrayList<>();
		String email = trimToNull(form.email());
		if (email == null) {
			errors.add(new FieldError("email", "Email is required"));
		}
		ManagedDetails details = validateDetails(form, errors);
		if (trimToNull(form.mobile()) == null) {
			errors.add(new FieldError("mobile", "Mobile is required"));
		}
		String password = form.password() == null || form.password().isBlank() ? null : form.password();
		if (password != null && password.length() < 8) {
			errors.add(new FieldError("password", "The password must be at least 8 characters"));
		}
		Set<UUID> courseIds = validateCourses(form.courseIds(), true, errors);
		if (!errors.isEmpty()) {
			throw new FieldValidationException("The student details are not valid", errors);
		}
		// Friendly pre-check; the UNIQUE (tenant_id, email) constraint is the race-safe guard.
		if (userProvisioningApi.existsByEmailIgnoreCase(email)) {
			throw new ConflictException("A user with this email already exists");
		}

		String temporaryPassword = null;
		ProvisionedUser provisioned;
		if (password == null) {
			provisioned = userProvisioningApi.provisionTenantUser(email, unusablePassword(), "STUDENT", true);
			// Generated once, returned once (never logged or stored in plain text).
			temporaryPassword = userProvisioningApi.resetPassword(provisioned.userId());
		}
		else {
			provisioned = userProvisioningApi.provisionTenantUser(email, password, "STUDENT", true);
		}

		StudentProfile profile = new StudentProfile(tenantContext.getTenantId(), provisioned.userId(),
				details.firstName());
		profile.updateManagedDetails(details);
		profile = studentProfileRepository.saveAndFlush(profile);
		// ADR-031: the student's mobile is also the sign-in mobile (normalised; 409 if taken).
		userProvisioningApi.setLoginMobile(provisioned.userId(), details.mobile(), "mobile");
		for (UUID courseId : courseIds) {
			courseParticipationApi.enrollOnBehalf(provisioned.userId(), courseId);
		}

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("via", "student_management");
		metadata.put("courseIds", courseIds.stream().map(UUID::toString).toList());
		metadata.put("passwordGenerated", temporaryPassword != null);
		auditLogApi.record(new AuditLogEntry(actorId(), "student.created", TARGET, profile.getId(), null, metadata));
		return new StudentCreatedResponse(toDetail(profile), temporaryPassword);
	}

	@Transactional(readOnly = true)
	public StudentDetail detail(UUID id) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		return toDetail(requireManagedStudent(id));
	}

	/** Edit: never touches password, index number, QR token or flags; course selection is add-only. */
	public StudentDetail update(UUID id, StudentRegistrationForm form) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		StudentProfile profile = requireManagedStudent(id);
		List<FieldError> errors = new ArrayList<>();
		ManagedDetails details = validateDetails(form, errors);
		if (trimToNull(form.mobile()) == null) {
			errors.add(new FieldError("mobile", "Mobile is required"));
		}
		String email = trimToNull(form.email());
		if (email != null) {
			String current = summaryOf(profile).map(TenantUserSummary::email).orElse(null);
			if (!email.equalsIgnoreCase(String.valueOf(current))) {
				errors.add(new FieldError("email", "The sign-in email cannot be changed here"));
			}
		}
		Set<UUID> courseIds = validateCourses(form.courseIds(), false, errors);
		if (!errors.isEmpty()) {
			throw new FieldValidationException("The student details are not valid", errors);
		}

		List<String> changed = changedFields(profile, details);
		profile.updateManagedDetails(details);
		studentProfileRepository.saveAndFlush(profile);
		userProvisioningApi.setLoginMobile(profile.getUserId(), details.mobile(), "mobile");
		Set<UUID> existing = courseParticipationApi.findParticipatingCourseIds(profile.getUserId());
		List<String> added = new ArrayList<>();
		for (UUID courseId : courseIds) {
			if (!existing.contains(courseId) && courseParticipationApi.enrollOnBehalf(profile.getUserId(), courseId)) {
				added.add(courseId.toString());
			}
		}

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("changedFields", changed);
		metadata.put("addedCourseIds", added);
		auditLogApi.record(new AuditLogEntry(actorId(), "student.updated", TARGET, profile.getId(), null, metadata));
		return toDetail(profile);
	}

	// ------------------------------------------------------------------
	// Approve / remove.
	// ------------------------------------------------------------------

	public StudentDetail approve(UUID id) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		StudentProfile profile = requireManagedStudent(id);
		if (!profile.isPendingApproval()) {
			throw new ConflictException("This student is not awaiting approval");
		}
		userProvisioningApi.activateTenantUser(profile.getUserId());
		profile.approve();
		studentProfileRepository.save(profile);
		auditLogApi.record(new AuditLogEntry(actorId(), "student.approved", TARGET, profile.getId(), null,
				Map.of("before", "PENDING", "after", "ACTIVE")));
		return toDetail(profile);
	}

	/**
	 * Soft removal from this tenant: the account is suspended and its sessions revoked;
	 * payments, ledger, exams, attendance, audit and enrollment history stay untouched.
	 */
	public void remove(UUID id, String reason) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.DELETE);
		StudentProfile profile = requireManagedStudent(id);
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.isEmpty()) {
			throw new FieldValidationException("A reason is required",
					List.of(new FieldError("reason", "A reason is required")));
		}
		String statusBefore = summaryOf(profile).map(TenantUserSummary::status).orElse(null);
		profile.markRemoved(actorId(), Instant.now(), trimmed);
		studentProfileRepository.save(profile);
		userProvisioningApi.suspendTenantUser(profile.getUserId());
		userProvisioningApi.revokeAllSessions(profile.getUserId());
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (statusBefore != null) {
			metadata.put("statusBefore", statusBefore);
		}
		metadata.put("statusAfter", "REMOVED");
		auditLogApi.record(new AuditLogEntry(actorId(), "student.removed", TARGET, profile.getId(), trimmed,
				metadata));
	}

	// ------------------------------------------------------------------
	// QR (spec section 7) - identification only.
	// ------------------------------------------------------------------

	public record QrView(String payload, Instant rotatedAt) {
	}

	public record ResolvedStudent(UUID id, String indexNumber, String fullName) {
	}

	@Transactional(readOnly = true)
	public QrView qr(UUID id) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		StudentProfile profile = requireManagedStudent(id);
		return new QrView(StudentQrTokens.payload(profile.getQrToken()), profile.getQrRotatedAt());
	}

	/** Rotation revokes every printed card carrying the old token (audited; token values never logged). */
	public QrView rotateQr(UUID id) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		StudentProfile profile = requireManagedStudent(id);
		Instant now = Instant.now();
		Instant previousRotation = profile.getQrRotatedAt();
		profile.rotateQrToken(now);
		studentProfileRepository.saveAndFlush(profile);
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (previousRotation != null) {
			metadata.put("previousRotatedAt", previousRotation.toString());
		}
		metadata.put("rotatedAt", now.toString());
		auditLogApi.record(new AuditLogEntry(actorId(), "student.qr_rotated", TARGET, profile.getId(), null,
				metadata));
		return new QrView(StudentQrTokens.payload(profile.getQrToken()), now);
	}

	/** Staff lookup of a scanned payload in THIS tenant; anything else is 404. No side effects. */
	@Transactional(readOnly = true)
	public ResolvedStudent resolveQr(String payload) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		String token = StudentQrTokens.tokenOf(payload);
		if (token == null) {
			throw new NotFoundException("Student not found");
		}
		StudentProfile profile = studentProfileRepository
			.findOne((root, query, cb) -> cb.and(cb.equal(root.get("qrToken"), token), cb.isNull(root.get("removedAt"))))
			.orElseThrow(() -> new NotFoundException("Student not found"));
		return new ResolvedStudent(profile.getId(), profile.getIndexNumber(), profile.getName());
	}

	@Override
	@Transactional(readOnly = true)
	public List<StudentQrLabel> findQrLabels(java.util.Collection<UUID> studentProfileIds) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		Set<UUID> ids = new LinkedHashSet<>();
		if (studentProfileIds != null) {
			studentProfileIds.stream().filter(Objects::nonNull).forEach(ids::add);
		}
		if (ids.size() > MAX_LABELS) {
			throw new FieldValidationException("Too many students selected",
					List.of(new FieldError("studentIds", "Select at most " + MAX_LABELS + " students")));
		}
		if (ids.isEmpty()) {
			return List.of();
		}
		// findAllById is tenant-scoped: another tenant's ids are simply absent.
		return studentProfileRepository.findAllById(ids)
			.stream()
			.filter(profile -> !profile.isRemoved())
			.sorted(java.util.Comparator
				.comparingInt((StudentProfile profile) -> String.valueOf(profile.getIndexNumber()).length())
				.thenComparing(profile -> String.valueOf(profile.getIndexNumber())))
			.map(profile -> new StudentQrLabel(profile.getId(), profile.getIndexNumber(), profile.getName(),
					StudentQrTokens.payload(profile.getQrToken())))
			.toList();
	}

	// ------------------------------------------------------------------
	// Shared helpers (also used by the photo / remark / impersonation services).
	// ------------------------------------------------------------------

	/** A non-removed student of this tenant, else 404. */
	@Transactional(readOnly = true)
	public StudentProfile requireManagedStudent(UUID id) {
		return studentProfileRepository.findById(id)
			.filter(profile -> !profile.isRemoved())
			.orElseThrow(() -> new NotFoundException("Student account not found"));
	}

	@Transactional(readOnly = true)
	public StudentDetail toDetail(StudentProfile profile) {
		Optional<TenantUserSummary> summary = summaryOf(profile);
		Instant lastLogin = userProvisioningApi.findLastLoginAt(List.of(profile.getUserId())).get(profile.getUserId());
		String photoUrl = studentProfileMediaApi.signUrl(profile.getProfileMediaFileId())
			.map(StudentProfileMediaApi.SignedImage::url)
			.orElse(null);
		List<UUID> courses = courseParticipationApi.findParticipatingCourseIds(profile.getUserId())
			.stream()
			.sorted()
			.toList();
		return new StudentDetail(profile.getId(), profile.getIndexNumber(), profile.getFirstName(),
				profile.getLastName(), profile.getName(), summary.map(TenantUserSummary::email).orElse(null),
				profile.getMobile(), profile.getWhatsappNumber(), profile.getDateOfBirth(), profile.getAddressLine(),
				profile.getAddressLine2(), profile.getCity(), profile.getPostalCode(), profile.getGuardianName(),
				profile.getGuardianPhone(), profile.getGuardianNic(), profile.getFixedPhone(), profile.getSchool(),
				profile.getAlYear() == null ? null : profile.getAlYear().intValue(), profile.getStream(),
				summary.map(TenantUserSummary::status).orElse(null), profile.isPendingApproval(),
				profile.getCreatedAt(), lastLogin, photoUrl, courses);
	}

	private Optional<TenantUserSummary> summaryOf(StudentProfile profile) {
		return userProvisioningApi.findTenantUserSummaries(List.of(profile.getUserId())).stream().findFirst();
	}

	private static UUID actorId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	private ManagedDetails validateDetails(StudentRegistrationForm form, List<FieldError> errors) {
		String firstName = trimToNull(form.firstName());
		if (firstName == null) {
			errors.add(new FieldError("firstName", "First name is required"));
			firstName = "";
		}
		LocalDate dateOfBirth = null;
		String rawDob = trimToNull(form.dateOfBirth());
		if (rawDob != null) {
			try {
				dateOfBirth = LocalDate.parse(rawDob);
				if (dateOfBirth.isBefore(MIN_DATE_OF_BIRTH)) {
					errors.add(new FieldError("dateOfBirth", "The date of birth must be on or after 1900-01-01"));
				}
				else if (dateOfBirth.isAfter(LocalDate.now(tenantZone()))) {
					errors.add(new FieldError("dateOfBirth", "The date of birth cannot be in the future"));
				}
			}
			catch (DateTimeParseException ex) {
				errors.add(new FieldError("dateOfBirth", "Enter a valid date (YYYY-MM-DD)"));
			}
		}
		return new ManagedDetails(firstName, trimToNull(form.lastName()), trimToNull(form.whatsappNumber()),
				dateOfBirth, trimToNull(form.mobile()), trimToNull(form.addressLine()), trimToNull(form.addressLine2()),
				trimToNull(form.city()), trimToNull(form.postalCode()), trimToNull(form.guardianName()),
				trimToNull(form.guardianPhone()), trimToNull(form.guardianNic()), trimToNull(form.fixedPhone()),
				trimToNull(form.school()), form.alYear() == null ? null : form.alYear().shortValue(),
				trimToNull(form.stream()));
	}

	/** Every id must be a non-archived, non-draft course of this tenant (tenant-scoped lookup). */
	private Set<UUID> validateCourses(List<UUID> requested, boolean required, List<FieldError> errors) {
		Set<UUID> ids = new LinkedHashSet<>();
		if (requested != null) {
			requested.stream().filter(Objects::nonNull).forEach(ids::add);
		}
		if (required && ids.isEmpty()) {
			errors.add(new FieldError("courseIds", "Select at least one course"));
			return ids;
		}
		for (UUID courseId : ids) {
			Optional<CourseCatalogInfo> course = courseLookupApi.findCatalogInfo(courseId)
				.filter(info -> !info.archived() && !"DRAFT".equals(info.status()));
			if (course.isEmpty()) {
				errors.add(new FieldError("courseIds", "A selected course is not available"));
				break;
			}
		}
		return ids;
	}

	private static List<String> changedFields(StudentProfile profile, ManagedDetails next) {
		List<String> changed = new ArrayList<>();
		compare(changed, "firstName", profile.getFirstName(), next.firstName());
		compare(changed, "lastName", profile.getLastName(), next.lastName());
		compare(changed, "whatsappNumber", profile.getWhatsappNumber(), next.whatsappNumber());
		compare(changed, "dateOfBirth", profile.getDateOfBirth(), next.dateOfBirth());
		compare(changed, "mobile", profile.getMobile(), next.mobile());
		compare(changed, "addressLine", profile.getAddressLine(), next.addressLine1());
		compare(changed, "addressLine2", profile.getAddressLine2(), next.addressLine2());
		compare(changed, "city", profile.getCity(), next.city());
		compare(changed, "postalCode", profile.getPostalCode(), next.postalCode());
		compare(changed, "guardianName", profile.getGuardianName(), next.guardianName());
		compare(changed, "guardianPhone", profile.getGuardianPhone(), next.guardianPhone());
		compare(changed, "guardianNic", profile.getGuardianNic(), next.guardianNic());
		compare(changed, "fixedPhone", profile.getFixedPhone(), next.homePhone());
		compare(changed, "school", profile.getSchool(), next.school());
		compare(changed, "alYear", profile.getAlYear(), next.alYear());
		compare(changed, "stream", profile.getStream(), next.alStream());
		return changed;
	}

	private static void compare(List<String> changed, String field, Object before, Object after) {
		if (!Objects.equals(before, after)) {
			changed.add(field);
		}
	}

	ZoneId tenantZone() {
		try {
			Object configured = tenantConfigApi
				.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zone && !zone.isBlank()) {
				return ZoneId.of(zone.strip());
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// fall through to UTC
		}
		return ZoneId.of("UTC");
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** A random password nobody knows; replaced at once by the generated temporary password. */
	private static String unusablePassword() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Refusal used by the impersonation start for pending / non-active targets. */
	static RequestRefusedException notActive(String message) {
		return new RequestRefusedException(org.springframework.http.HttpStatus.CONFLICT,
				ApiErrorCodes.STUDENT_NOT_ACTIVE, message);
	}

}
