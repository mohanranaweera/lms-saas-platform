package com.lms.usermanagement.student.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Student Management (MVP-006) manual account creation, staff
 * reads/edits, and the student's own self-service profile view/edit.
 * {@code user-management} owns only the {@code StudentProfile} row (name);
 * the credential row itself (email, password hash, role, status) is
 * delegated entirely to {@code identity-access-service}'s {@link
 * UserProvisioningApi} - this class never constructs, hashes, or persists a
 * password/credential value itself, per {@code .claude/rules/security.md}'s
 * credential-creation contract boundary.
 *
 * <p>Every staff-facing method independently re-checks {@code STUDENTS}
 * permission via {@link PermissionCheckService#requirePermission} before
 * doing anything else - defense in depth on top of {@code
 * StudentController}'s {@code @PreAuthorize} gates, mirroring {@code
 * StaffService}'s established pattern. The self-service methods ({@link
 * #getOwnProfile()}/{@link #updateOwnProfile(String)}) deliberately do NOT
 * call {@link PermissionCheckService} at all - {@code DomainArea.STUDENTS}
 * is modeled for staff-type roles only; {@code Role.STUDENT} has no entry in
 * that matrix by design, so gating a student's own-profile access through it
 * would incorrectly default-deny. Those two methods are instead structurally
 * safe: they take no id parameter at all and resolve the caller's own {@code
 * userId} from {@link AuthenticatedPrincipalHolder}, so a student can never
 * substitute another student's id.
 *
 * <p>Only Stage 1 (schema) + Stage 2 (manual creation by Tenant Admin/Student
 * Support) + the self-service {@code /me} view/edit slice of MVP-006's plan
 * are implemented here - no delete/status-change, bulk-import, self-
 * registration, teacher-roster, or history-composition endpoint.
 */
@Service
@Transactional
public class StudentService {

	private static final Logger log = LoggerFactory.getLogger(StudentService.class);

	private final UserProvisioningApi userProvisioningApi;

	private final StudentProfileRepository studentProfileRepository;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	public StudentService(UserProvisioningApi userProvisioningApi, StudentProfileRepository studentProfileRepository,
			TenantContext tenantContext, PermissionCheckService permissionCheckService) {
		this.userProvisioningApi = userProvisioningApi;
		this.studentProfileRepository = studentProfileRepository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * Creates a student account: the {@code tenant_user} credential row and
	 * this module's {@code StudentProfile} row are both written in this one
	 * transaction (in-process call into {@code identity-access-service}'s
	 * {@code api}, same DB transaction, per {@code .claude/rules/backend.md}'s
	 * transaction-boundary rule). The role is always the literal {@code
	 * "STUDENT"} - never a parameter, never derived from any request field,
	 * unlike Staff Management's caller-supplied {@code roleCode} - since this
	 * method only ever provisions student accounts. {@code mustChangePassword}
	 * is always {@code true} - this is a manual creation by an admin/support
	 * actor, not self-registration (which is out of scope for this pass).
	 */
	public StudentAccount createStudent(String name, String email, String rawPassword) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);

		// Friendlier pre-check for the common case; NOT the race-safe guard -
		// identity-access-service's UNIQUE (tenant_id, email) constraint,
		// enforced inside provisionTenantUser, is what actually prevents a
		// concurrent double-creation from both succeeding (TOCTOU-safe).
		if (userProvisioningApi.existsByEmail(email)) {
			throw new ConflictException("A student account with this email already exists");
		}

		ProvisionedUser provisioned = userProvisioningApi.provisionTenantUser(email, rawPassword, "STUDENT", true);

		StudentProfile profile = new StudentProfile(tenantContext.getTenantId(), provisioned.userId(), name);
		profile = studentProfileRepository.save(profile);

		// Lightweight trace/audit hook only, pending the not-yet-built
		// audit-log-management module (plan §16 - whether student CRUD
		// requires a formal audit-log entry is an open decision, not resolved
		// here). This log line does not itself satisfy that open requirement.
		log.info("Student profile {} created in tenant {} by admin user {}", profile.getId(),
				tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());

		// A freshly-provisioned tenant_user always starts ACTIVE (see
		// TenantUser's constructor) - no extra read needed to know this.
		return new StudentAccount(profile.getId(), name, provisioned.email(), "STUDENT", "ACTIVE");
	}

	@Transactional(readOnly = true)
	public List<StudentAccount> listStudents() {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);

		List<StudentProfile> profiles = studentProfileRepository.findAll();
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(profiles);
		return profiles.stream()
			.map(profile -> toStudentAccount(profile, summariesByUserId))
			.filter(Objects::nonNull)
			.toList();
	}

	@Transactional(readOnly = true)
	public StudentAccount getStudent(UUID id) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);

		// TenantAwareRepository scopes findById to the resolved tenant
		// context already - a tenant B student id is structurally invisible
		// here, surfacing as empty (-> 404), never a cross-tenant read.
		StudentProfile profile = studentProfileRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Student account not found"));
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		StudentAccount account = toStudentAccount(profile, summariesByUserId);
		if (account == null) {
			// Data-integrity inconsistency (an FK-backed tenant_user row
			// vanished) rather than a legitimate "not found" - still surfaced
			// as 404 rather than leaking a 500 with internal detail.
			throw new NotFoundException("Student account not found");
		}
		return account;
	}

	public StudentAccount updateStudent(UUID id, String name) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);

		StudentProfile profile = studentProfileRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Student account not found"));
		profile.rename(name);
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		StudentAccount account = toStudentAccount(profile, summariesByUserId);
		if (account == null) {
			throw new NotFoundException("Student account not found");
		}

		// Lightweight trace/audit hook only, pending the not-yet-built
		// audit-log-management module (plan §16) - see createStudent's
		// identical comment above.
		log.info("Student profile {} updated in tenant {} by admin user {}", profile.getId(),
				tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());

		return account;
	}

	/**
	 * Self-service read of the caller's own student profile. Deliberately
	 * takes no id parameter - resolves {@code userId} from {@link
	 * AuthenticatedPrincipalHolder} and looks the profile up via the
	 * repository's inherited, tenant-scoped {@code findOne(Specification)},
	 * so a student can never substitute another student's id (see class
	 * javadoc). Safe to trust the caller's own JWT-derived {@code userId} as
	 * belonging to the current tenant context, since the request is already
	 * tenant-resolved before this method runs.
	 */
	@Transactional(readOnly = true)
	public StudentAccount getOwnProfile() {
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		StudentProfile profile = studentProfileRepository
			.findOne((root, query, cb) -> cb.equal(root.get("userId"), userId))
			.orElseThrow(() -> new NotFoundException("Student account not found"));
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		StudentAccount account = toStudentAccount(profile, summariesByUserId);
		if (account == null) {
			throw new NotFoundException("Student account not found");
		}
		return account;
	}

	/**
	 * Self-service edit of the caller's own student profile - same
	 * no-id-parameter, self-resolved design as {@link #getOwnProfile()}.
	 */
	public StudentAccount updateOwnProfile(String name) {
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		StudentProfile profile = studentProfileRepository
			.findOne((root, query, cb) -> cb.equal(root.get("userId"), userId))
			.orElseThrow(() -> new NotFoundException("Student account not found"));
		profile.rename(name);
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		StudentAccount account = toStudentAccount(profile, summariesByUserId);
		if (account == null) {
			throw new NotFoundException("Student account not found");
		}

		// Lightweight trace/audit hook only, pending the not-yet-built
		// audit-log-management module (plan §16). Actor id and target id are
		// the same value here (a student editing their own profile) - that
		// is expected and fine.
		log.info("Student profile {} updated in tenant {} by self-service user {}", profile.getId(),
				tenantContext.getTenantId(), userId);

		return account;
	}

	private Map<UUID, TenantUserSummary> summariesByUserId(List<StudentProfile> profiles) {
		List<UUID> userIds = profiles.stream().map(StudentProfile::getUserId).toList();
		return userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
	}

	private static StudentAccount toStudentAccount(StudentProfile profile,
			Map<UUID, TenantUserSummary> summariesByUserId) {
		TenantUserSummary summary = summariesByUserId.get(profile.getUserId());
		if (summary == null) {
			return null;
		}
		return new StudentAccount(profile.getId(), profile.getName(), summary.email(), summary.roleCode(),
				summary.status());
	}

}
