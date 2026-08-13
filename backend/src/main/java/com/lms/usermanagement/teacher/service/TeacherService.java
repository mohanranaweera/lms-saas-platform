package com.lms.usermanagement.teacher.service;

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
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Teacher Management (MVP-007) account creation, approval
 * review, and reads. {@code user-management} owns only the {@code
 * TeacherProfile} row (name, approval workflow); the credential row itself
 * (email, password hash, role, login-gate status) is delegated entirely to
 * {@code identity-access-service}'s {@link UserProvisioningApi} - this class
 * never constructs, hashes, or persists a password/credential value itself,
 * per {@code .claude/rules/security.md}'s credential-creation contract
 * boundary. Directly mirrors {@code StaffService}'s shape and defense-in-depth
 * pattern.
 *
 * <p>Every public method independently re-checks {@code TEACHERS} permission
 * via {@link PermissionCheckService#requirePermission} before doing anything
 * else - defense in depth on top of {@link
 * com.lms.usermanagement.teacher.web.TeacherController}'s {@code
 * @PreAuthorize} gates, matching the pattern already established for {@code
 * StaffService} (commit {@code d265597}).
 *
 * <p><b>Login-gate mechanism (approved plan §7/§21-item-2):</b> a freshly
 * created teacher's {@code tenant_user} row is immediately suspended
 * (blocking login) and only reactivated on approval - {@code
 * tenant_user.status} is the login-gate mechanism, {@code
 * TeacherProfile.approvalStatus} is the domain-meaningful "why."
 */
@Service
@Transactional
public class TeacherService {

	/**
	 * Approval-authority enforcement path (approved plan §2/§21-item-1,
	 * recommendation (a)): {@code TEACHERS}/{@code CREATE_EDIT} alone also
	 * grants Course Coordinator, so an additional, narrower service-layer
	 * check restricts {@link #approveTeacher}/{@link #rejectTeacher} to
	 * literally this role. Compared against {@link
	 * AuthenticatedPrincipalHolder#get()}'s role string, never {@code
	 * com.lms.identityaccessservice.domain.Role} (a domain-package boundary
	 * import this module must not make) - the same string-literal idiom
	 * {@code StaffService.ASSIGNABLE_STAFF_ROLES} already uses for role-code
	 * checks.
	 */
	private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

	private final UserProvisioningApi userProvisioningApi;

	private final TeacherProfileRepository teacherProfileRepository;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	public TeacherService(UserProvisioningApi userProvisioningApi, TeacherProfileRepository teacherProfileRepository,
			TenantContext tenantContext, PermissionCheckService permissionCheckService) {
		this.userProvisioningApi = userProvisioningApi;
		this.teacherProfileRepository = teacherProfileRepository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * Creates a teacher account: the {@code tenant_user} credential row and
	 * this module's {@code TeacherProfile} row are both written in this one
	 * transaction (in-process call into {@code identity-access-service}'s
	 * {@code api}, same DB transaction). {@code mustChangePassword} is always
	 * {@code true} - there is no self-registration path for teachers. Role is
	 * always literally {@code "TEACHER"} - never a client-supplied field,
	 * unlike Staff's 7-way role choice. The freshly-provisioned row is
	 * immediately suspended so the account cannot log in while its {@code
	 * TeacherProfile} is {@code PENDING}.
	 */
	public TeacherAccount createTeacher(String name, String email, String rawPassword) {
		permissionCheckService.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT);

		// Friendlier pre-check for the common case; NOT the race-safe guard -
		// identity-access-service's UNIQUE (tenant_id, email) constraint,
		// enforced inside provisionTenantUser, is what actually prevents a
		// concurrent double-creation from both succeeding (TOCTOU-safe).
		if (userProvisioningApi.existsByEmail(email)) {
			throw new ConflictException("A teacher account with this email already exists");
		}

		ProvisionedUser provisioned = userProvisioningApi.provisionTenantUser(email, rawPassword, "TEACHER", true);

		// Blocks login/course-assignment while the approval decision is
		// pending (approved plan §4/§7) - the account must not be able to log
		// in until a Tenant Admin approves it.
		userProvisioningApi.suspendTenantUser(provisioned.userId());

		TeacherProfile profile = new TeacherProfile(tenantContext.getTenantId(), provisioned.userId(), name);
		profile = teacherProfileRepository.save(profile);

		// A freshly-suspended tenant_user's AccountStatus is SUSPENDED (see
		// UserProvisioningApi#suspendTenantUser) - uppercase, matching the
		// same AccountStatus#name() convention TenantUserSummary#status()
		// uses elsewhere in this class (no extra read needed to know this).
		return new TeacherAccount(profile.getId(), name, provisioned.email(), profile.getApprovalStatus().name(),
				"SUSPENDED", null, null);
	}

	/**
	 * Transitions a {@code PENDING} teacher to {@code APPROVED}:
	 * {@code teacher_profile.approval_status -> APPROVED},
	 * {@code approved_by}/{@code approved_at} recorded, and the
	 * corresponding {@code tenant_user} row reactivated so the teacher can
	 * now log in.
	 */
	public TeacherAccount approveTeacher(UUID id) {
		permissionCheckService.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT);
		requireTenantAdmin();

		TeacherProfile profile = loadProfile(id);
		UUID approverId = AuthenticatedPrincipalHolder.get().userId();
		try {
			profile.approve(approverId, Instant.now());
		}
		catch (IllegalStateException ex) {
			throw new InvalidApprovalStateException(profile.getApprovalStatus().name());
		}

		userProvisioningApi.activateTenantUser(profile.getUserId());

		profile = teacherProfileRepository.save(profile);
		return requireTeacherAccount(profile);
	}

	/**
	 * Transitions a {@code PENDING} teacher to {@code REJECTED}:
	 * {@code teacher_profile.approval_status -> REJECTED},
	 * {@code approved_by}/{@code approved_at} recorded (a rejection is
	 * equally a recorded decision, per approved plan §7). {@code
	 * tenant_user.status} stays {@code suspended} permanently - a rejected
	 * teacher never gains login capability, so no {@code
	 * UserProvisioningApi} status call is needed here.
	 */
	public TeacherAccount rejectTeacher(UUID id) {
		permissionCheckService.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT);
		requireTenantAdmin();

		TeacherProfile profile = loadProfile(id);
		UUID reviewerId = AuthenticatedPrincipalHolder.get().userId();
		try {
			profile.reject(reviewerId, Instant.now());
		}
		catch (IllegalStateException ex) {
			throw new InvalidApprovalStateException(profile.getApprovalStatus().name());
		}

		profile = teacherProfileRepository.save(profile);
		return requireTeacherAccount(profile);
	}

	@Transactional(readOnly = true)
	public List<TeacherAccount> listTeachers(ApprovalStatus filter) {
		permissionCheckService.requirePermission(DomainArea.TEACHERS, PermissionAction.VIEW);

		List<TeacherProfile> profiles = filter == null ? teacherProfileRepository.findAll()
				: teacherProfileRepository.findByApprovalStatus(filter);
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(profiles);
		return profiles.stream()
			.map(profile -> toTeacherAccount(profile, summariesByUserId))
			.filter(Objects::nonNull)
			.toList();
	}

	@Transactional(readOnly = true)
	public TeacherAccount getTeacher(UUID id) {
		permissionCheckService.requirePermission(DomainArea.TEACHERS, PermissionAction.VIEW);

		// TenantAwareRepository scopes findById to the resolved tenant
		// context already - a tenant B teacher id is structurally invisible
		// here, surfacing as empty (-> 404), never a cross-tenant read.
		TeacherProfile profile = teacherProfileRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Teacher account not found"));
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		TeacherAccount account = toTeacherAccount(profile, summariesByUserId);
		if (account == null) {
			// Data-integrity inconsistency (an FK-backed tenant_user row
			// vanished) rather than a legitimate "not found" - still surfaced
			// as 404 rather than leaking a 500 with internal detail.
			throw new NotFoundException("Teacher account not found");
		}
		return account;
	}

	private TeacherProfile loadProfile(UUID id) {
		// TenantAwareRepository scopes findById to the resolved tenant
		// context already - a tenant B teacher id is structurally invisible
		// here, surfacing as 404, never a cross-tenant mutation.
		return teacherProfileRepository.findById(id).orElseThrow(() -> new NotFoundException("Teacher account not found"));
	}

	/**
	 * Re-reads the just-mutated {@code tenant_user} row's current status
	 * (post {@code suspendTenantUser}/{@code activateTenantUser}) rather
	 * than assuming a hardcoded literal, so the returned {@code
	 * TeacherAccount} always reflects the actual persisted login-gate state,
	 * not just the state this method intended to set.
	 */
	private TeacherAccount requireTeacherAccount(TeacherProfile profile) {
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		TeacherAccount account = toTeacherAccount(profile, summariesByUserId);
		if (account == null) {
			throw new NotFoundException("Teacher account not found");
		}
		return account;
	}

	private void requireTenantAdmin() {
		String role = AuthenticatedPrincipalHolder.get().role();
		if (!TENANT_ADMIN_ROLE.equals(role)) {
			throw new AccessDeniedException("Only a Tenant Admin may approve or reject a teacher account");
		}
	}

	private Map<UUID, TenantUserSummary> summariesByUserId(List<TeacherProfile> profiles) {
		List<UUID> userIds = profiles.stream().map(TeacherProfile::getUserId).toList();
		return userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
	}

	private static TeacherAccount toTeacherAccount(TeacherProfile profile,
			Map<UUID, TenantUserSummary> summariesByUserId) {
		TenantUserSummary summary = summariesByUserId.get(profile.getUserId());
		if (summary == null) {
			return null;
		}
		return new TeacherAccount(profile.getId(), profile.getName(), summary.email(),
				profile.getApprovalStatus().name(), summary.status(), profile.getApprovedBy(),
				profile.getApprovedAt());
	}

}
