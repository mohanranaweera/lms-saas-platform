package com.lms.usermanagement.staff.service;

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
import com.lms.usermanagement.staff.domain.StaffProfile;
import com.lms.usermanagement.staff.repository.StaffProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Staff Management (MVP-005) account creation and reads.
 * {@code user-management} owns only the {@code StaffProfile} row (name); the
 * credential row itself (email, password hash, role, status) is delegated
 * entirely to {@code identity-access-service}'s {@link UserProvisioningApi} -
 * this class never constructs, hashes, or persists a password/credential
 * value itself, per {@code .claude/rules/security.md}'s credential-creation
 * contract boundary.
 *
 * <p>Every public method independently re-checks {@code STAFF_AND_ROLES}
 * permission via {@link PermissionCheckService#requirePermission} before
 * doing anything else - defense in depth on top of {@link
 * com.lms.usermanagement.staff.web.StaffController}'s {@code @PreAuthorize}
 * gates, so a future caller in this same JVM/package (a batch job, a second
 * controller, a bug that adds an unguarded endpoint) cannot reach a
 * privileged staff-account operation by bypassing the controller layer -
 * {@code @PreAuthorize} alone would otherwise be the sole enforcement point.
 */
@Service
@Transactional
public class StaffService {

	/**
	 * The fixed 7 staff sub-roles Staff Management is allowed to assign,
	 * deliberately narrower than the full {@code Role} enum {@code
	 * identity-access-service} validates against - {@code TENANT_ADMIN},
	 * {@code TEACHER}, {@code TEACHER_ASSISTANT}, and {@code STUDENT} are
	 * valid {@code Role} values elsewhere but must never be assignable
	 * through this flow, since they are provisioned by other flows.
	 */
	private static final Set<String> ASSIGNABLE_STAFF_ROLES = Set.of("FINANCE_STAFF", "COURSE_COORDINATOR",
			"STUDENT_SUPPORT", "CONTENT_MANAGER", "EXAM_MANAGER", "ATTENDANCE_OPERATOR", "READ_ONLY_AUDITOR");

	private final UserProvisioningApi userProvisioningApi;

	private final StaffProfileRepository staffProfileRepository;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	public StaffService(UserProvisioningApi userProvisioningApi, StaffProfileRepository staffProfileRepository,
			TenantContext tenantContext, PermissionCheckService permissionCheckService) {
		this.userProvisioningApi = userProvisioningApi;
		this.staffProfileRepository = staffProfileRepository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * Creates a staff account: the {@code tenant_user} credential row and
	 * this module's {@code StaffProfile} row are both written in this one
	 * transaction (in-process call into {@code identity-access-service}'s
	 * {@code api}, same DB transaction, per {@code .claude/rules/backend.md}'s
	 * transaction-boundary rule). {@code mustChangePassword} is always {@code
	 * true} - there is no self-registration path for staff, so every
	 * admin-created account must set its own credential at next login (per
	 * {@code docs/ui-ux/authentication-design-spec.md} §3.7). The raw login
	 * secret itself is never supplied by the caller: {@link
	 * UserProvisioningApi#provisionTenantUser} generates a random,
	 * high-entropy temporary password server-side and returns it via {@link
	 * ProvisionedUser#temporaryPassword()} - this method passes it straight
	 * through in the returned {@link StaffCreationResult} for {@code web} to
	 * hand back to the caller exactly once, and never persists or logs it
	 * itself.
	 */
	public StaffCreationResult createStaff(String name, String email, String roleCode) {
		permissionCheckService.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.CREATE_EDIT);

		if (!ASSIGNABLE_STAFF_ROLES.contains(roleCode)) {
			throw new InvalidStaffRoleException(roleCode);
		}

		// Friendlier pre-check for the common case; NOT the race-safe guard -
		// identity-access-service's UNIQUE (tenant_id, email) constraint,
		// enforced inside provisionTenantUser, is what actually prevents a
		// concurrent double-creation from both succeeding (TOCTOU-safe).
		if (userProvisioningApi.existsByEmail(email)) {
			throw new ConflictException("A staff account with this email already exists");
		}

		ProvisionedUser provisioned = userProvisioningApi.provisionTenantUser(email, roleCode, true);

		StaffProfile profile = new StaffProfile(tenantContext.getTenantId(), provisioned.userId(), name);
		profile = staffProfileRepository.save(profile);

		// A freshly-provisioned tenant_user always starts ACTIVE (see
		// TenantUser's constructor) - no extra read needed to know this.
		StaffAccount account = new StaffAccount(profile.getId(), name, provisioned.email(), roleCode, "ACTIVE");
		return new StaffCreationResult(account, provisioned.temporaryPassword());
	}

	/**
	 * Edits an existing staff account's assigned role ({@code
	 * PATCH /api/v1/staff/{id}}). {@code staffProfileId} is this module's own
	 * {@code StaffProfile} id (the same resource id every other {@code
	 * /api/v1/staff} endpoint addresses), tenant-scoped by {@link
	 * StaffProfileRepository} exactly like {@link #getStaff} - a cross-tenant
	 * id surfaces as {@link NotFoundException}, never a cross-tenant
	 * mutation.
	 *
	 * <p>Self-escalation defense in depth: if the authenticated caller's own
	 * user id equals the target profile's underlying {@code tenant_user} id,
	 * this throws {@link AccessDeniedException} before ever calling into
	 * {@link UserProvisioningApi}. This is structurally unreachable today
	 * (Tenant Admins are not provisioned through this staff flow, so a
	 * Tenant Admin's own id can never match a {@code StaffProfile}'s {@code
	 * userId}) - it is a cheap guard against a future regression, not a
	 * scenario expected in production.
	 */
	public StaffAccount updateStaffRole(UUID staffProfileId, String roleCode) {
		permissionCheckService.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.CREATE_EDIT);

		if (!ASSIGNABLE_STAFF_ROLES.contains(roleCode)) {
			throw new InvalidStaffRoleException(roleCode);
		}

		// TenantAwareRepository scopes findById to the resolved tenant
		// context already - a tenant B staff id is structurally invisible
		// here, surfacing as empty (-> 404), never a cross-tenant read or
		// mutation.
		StaffProfile profile = staffProfileRepository.findById(staffProfileId)
			.orElseThrow(() -> new NotFoundException("Staff account not found"));

		if (AuthenticatedPrincipalHolder.get().userId().equals(profile.getUserId())) {
			throw new AccessDeniedException("You cannot change your own role");
		}

		userProvisioningApi.updateTenantUserRole(profile.getUserId(), roleCode);

		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		StaffAccount account = toStaffAccount(profile, summariesByUserId);
		if (account == null) {
			// Same data-integrity-inconsistency fallback as getStaff: the
			// role update above already succeeded against a real tenant_user
			// row, so this would only happen if that row vanished between
			// the update and this re-read - still surfaced as 404, not 500.
			throw new NotFoundException("Staff account not found");
		}
		return account;
	}

	@Transactional(readOnly = true)
	public List<StaffAccount> listStaff() {
		permissionCheckService.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW);

		List<StaffProfile> profiles = staffProfileRepository.findAll();
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(profiles);
		return profiles.stream()
			.map(profile -> toStaffAccount(profile, summariesByUserId))
			.filter(Objects::nonNull)
			.toList();
	}

	@Transactional(readOnly = true)
	public StaffAccount getStaff(UUID id) {
		permissionCheckService.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW);

		// TenantAwareRepository scopes findById to the resolved tenant
		// context already - a tenant B staff id is structurally invisible
		// here, surfacing as empty (-> 404), never a cross-tenant read.
		StaffProfile profile = staffProfileRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Staff account not found"));
		Map<UUID, TenantUserSummary> summariesByUserId = summariesByUserId(List.of(profile));
		StaffAccount account = toStaffAccount(profile, summariesByUserId);
		if (account == null) {
			// Data-integrity inconsistency (an FK-backed tenant_user row
			// vanished) rather than a legitimate "not found" - still surfaced
			// as 404 rather than a leaking a 500 with internal detail.
			throw new NotFoundException("Staff account not found");
		}
		return account;
	}

	private Map<UUID, TenantUserSummary> summariesByUserId(List<StaffProfile> profiles) {
		List<UUID> userIds = profiles.stream().map(StaffProfile::getUserId).toList();
		return userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
	}

	private static StaffAccount toStaffAccount(StaffProfile profile, Map<UUID, TenantUserSummary> summariesByUserId) {
		TenantUserSummary summary = summariesByUserId.get(profile.getUserId());
		if (summary == null) {
			return null;
		}
		return new StaffAccount(profile.getId(), profile.getName(), summary.email(), summary.roleCode(),
				summary.status());
	}

}
