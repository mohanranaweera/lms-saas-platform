package com.lms.usermanagement.staff.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.common.logging.ContactMasking;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.staff.domain.StaffProfile;
import com.lms.usermanagement.staff.repository.StaffProfileRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff Management (MVP-005, reworked by STAFF-MGMT-02 / ADR-034). {@code user-management}
 * owns only the {@code staff_profile} row; the credential row (email, role, status, password)
 * is delegated to {@code identity-access-service}'s {@link UserProvisioningApi} - this class
 * never sees a password: new staff are INVITED and choose their own password via the
 * account-setup email.
 *
 * <p>Every public method re-checks its capability via {@link PermissionCheckService} (defense in
 * depth behind the controller's {@code @RequiresCapability} + {@code @PreAuthorize}). Tenant
 * identity comes from the trusted context only; another tenant's staff id is a 404. Every
 * mutation is audited in the same transaction (emails masked, no secrets).
 */
@Service
@Transactional
public class StaffService {

	/**
	 * Roles this flow may assign (server-derived; never the client's list): Operator, Manager and
	 * the seven seeded staff sub-roles (STAFF-MGMT-03 §6.1). Never Tenant Admin, Teacher, Teacher
	 * Assistant or Student.
	 */
	static final List<String> ASSIGNABLE_ROLES = List.of("OPERATOR", "MANAGER", "FINANCE_STAFF",
			"COURSE_COORDINATOR", "STUDENT_SUPPORT", "CONTENT_MANAGER", "EXAM_MANAGER", "ATTENDANCE_OPERATOR",
			"READ_ONLY_AUDITOR");

	/** Memberships this flow must never touch (owner/admin, teachers, students). */
	static final Set<String> PROTECTED_ROLES = Set.of("TENANT_ADMIN", "TEACHER", "TEACHER_ASSISTANT", "STUDENT");

	static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_EMAIL_MATCHES = 200;

	private static final String TARGET = "staff_profile";

	private final UserProvisioningApi userProvisioningApi;

	private final StaffProfileRepository staffProfileRepository;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	public StaffService(UserProvisioningApi userProvisioningApi, StaffProfileRepository staffProfileRepository,
			TenantContext tenantContext, PermissionCheckService permissionCheckService, AuditLogApi auditLogApi) {
		this.userProvisioningApi = userProvisioningApi;
		this.staffProfileRepository = staffProfileRepository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
	}

	// ------------------------------------------------------------------ reads

	@Transactional(readOnly = true)
	public Page<StaffAccount> searchStaff(String q, int page, int size) {
		permissionCheckService.requireCapability(Capability.STAFF_VIEW);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
				Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"), Sort.Order.asc("name"),
						Sort.Order.asc("id")));
		String term = (q == null || q.isBlank()) ? null : q.strip();
		List<UUID> emailMatches = (term == null) ? List.of()
				: userProvisioningApi.findTenantUserIdsByEmailContaining(term, MAX_EMAIL_MATCHES);
		Page<StaffProfile> profiles = staffProfileRepository.search(term, emailMatches, pageable);
		Map<UUID, TenantUserSummary> summaries = summariesByUserId(profiles.getContent());
		Map<UUID, Instant> lastLogins = userProvisioningApi
			.findLastLoginAt(profiles.getContent().stream().map(StaffProfile::getUserId).toList());
		Map<String, String> roleNames = userProvisioningApi.roleDisplayNames();
		Permissions perms = currentPermissions();
		List<StaffAccount> accounts = new ArrayList<>();
		for (StaffProfile profile : profiles.getContent()) {
			TenantUserSummary summary = summaries.get(profile.getUserId());
			if (summary != null) {
				accounts.add(toAccount(profile, summary, lastLogins.get(profile.getUserId()), roleNames, perms));
			}
		}
		return new org.springframework.data.domain.PageImpl<>(accounts, pageable, profiles.getTotalElements());
	}

	@Transactional(readOnly = true)
	public StaffAccount getStaff(UUID id) {
		permissionCheckService.requireCapability(Capability.STAFF_VIEW);
		return load(id).account();
	}

	/** Server-derived list of roles this flow may assign ({@link #ASSIGNABLE_ROLES}) with display names. */
	@Transactional(readOnly = true)
	public List<AssignableRole> assignableRoles() {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		Map<String, String> names = userProvisioningApi.roleDisplayNames();
		return ASSIGNABLE_ROLES.stream().map(code -> new AssignableRole(code, names.getOrDefault(code, code))).toList();
	}

	/** ADR-031: the staff member's sign-in mobile (E.164), or {@code null}. */
	@Transactional(readOnly = true)
	public String getLoginMobile(UUID id) {
		permissionCheckService.requireCapability(Capability.STAFF_VIEW);
		UUID userId = loadProfile(id).getUserId();
		return userProvisioningApi.findLoginMobiles(List.of(userId)).get(userId);
	}

	/** ADR-031: sets (blank = removes) the staff member's sign-in mobile; identity validates and audits. */
	public String setLoginMobile(UUID id, String rawMobile) {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		Loaded loaded = load(id);
		// Same protection as every other staff mutation: never self, never a protected membership
		// (a sign-in mobile is a login credential - ADR-031).
		requireNotSelf(loaded.profile(), "You cannot change your own sign-in mobile here");
		requireStaffMembership(loaded.summary());
		UUID userId = loaded.profile().getUserId();
		userProvisioningApi.setLoginMobile(userId, rawMobile, "loginMobile");
		return userProvisioningApi.findLoginMobiles(List.of(userId)).get(userId);
	}

	// ------------------------------------------------------------------ mutations

	/**
	 * Invites a staff member: an INVITED {@code tenant_user} (no password; account-setup email
	 * queued) plus the profile, in one transaction. 409 with a generic message when the email
	 * is already used in this tenant (race safe via the unique constraint).
	 */
	public StaffAccount createStaff(String firstName, String lastName, String email, String mobileNumber,
			String roleCode) {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		if (roleCode == null || !ASSIGNABLE_ROLES.contains(roleCode)) {
			throw new InvalidStaffRoleException(roleCode);
		}
		ProvisionedUser user = userProvisioningApi.inviteTenantUser(email, roleCode);
		StaffProfile profile = staffProfileRepository.save(new StaffProfile(tenantContext.getTenantId(), user.userId(),
				firstName.strip(), lastName.strip(), mobileNumber.strip()));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("email", ContactMasking.maskEmail(user.email()));
		metadata.put("role", roleCode);
		audit("staff.invited", profile.getId(), metadata);
		return load(profile.getId()).account();
	}

	public StaffAccount updateProfile(UUID id, String firstName, String lastName, String contactNumber) {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		Loaded loaded = load(id);
		requireStaffMembership(loaded.summary());
		StaffProfile profile = loaded.profile();
		List<String> changed = new ArrayList<>();
		String newFirst = firstName.strip();
		String newLast = (lastName == null || lastName.isBlank()) ? null : lastName.strip();
		String newContact = (contactNumber == null || contactNumber.isBlank()) ? null : contactNumber.strip();
		if (!newFirst.equals(profile.displayFirstName())) {
			changed.add("firstName");
		}
		if (!java.util.Objects.equals(newLast, profile.displayLastName())) {
			changed.add("lastName");
		}
		if (!java.util.Objects.equals(newContact, profile.getContactNumber())) {
			changed.add("contactNumber");
		}
		profile.updateProfile(newFirst, newLast, newContact);
		staffProfileRepository.save(profile);
		if (!changed.isEmpty()) {
			audit("staff.profile_updated", profile.getId(), Map.of("fields", changed));
		}
		return load(id).account();
	}

	/**
	 * Reassigns the membership's role (STAFF_ASSIGN_ROLES). Never self; never a Tenant Admin /
	 * Teacher / Student membership; target role must be server-assignable (Operator, Manager or a
	 * seeded staff sub-role). Effective on the member's next request.
	 */
	public StaffAccount changeRole(UUID id, String roleCode) {
		permissionCheckService.requireCapability(Capability.STAFF_ASSIGN_ROLES);
		if (roleCode == null || !ASSIGNABLE_ROLES.contains(roleCode)) {
			throw new InvalidStaffRoleException(roleCode);
		}
		Loaded loaded = load(id);
		requireNotSelf(loaded.profile(), "You cannot change your own role");
		requireStaffMembership(loaded.summary());
		String from = loaded.summary().roleCode();
		if (!from.equals(roleCode)) {
			userProvisioningApi.changeRole(loaded.profile().getUserId(), roleCode);
			audit("staff.role_changed", id, Map.of("from", from, "to", roleCode));
		}
		return load(id).account();
	}

	/** Suspends the account and revokes all its sessions in this tenant (other tenants untouched). */
	public StaffAccount deactivate(UUID id) {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		Loaded loaded = load(id);
		requireNotSelf(loaded.profile(), "You cannot deactivate your own account");
		requireStaffMembership(loaded.summary());
		if (!"SUSPENDED".equals(loaded.summary().status())) {
			userProvisioningApi.suspendTenantUser(loaded.profile().getUserId());
			userProvisioningApi.revokeAllSessions(loaded.profile().getUserId());
			audit("staff.deactivated", id, Map.of("previousStatus", loaded.summary().status()));
		}
		return load(id).account();
	}

	public StaffAccount reactivate(UUID id) {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		Loaded loaded = load(id);
		requireNotSelf(loaded.profile(), "You cannot reactivate your own account");
		requireStaffMembership(loaded.summary());
		if ("SUSPENDED".equals(loaded.summary().status())) {
			userProvisioningApi.activateTenantUser(loaded.profile().getUserId());
			audit("staff.reactivated", id, null);
		}
		return load(id).account();
	}

	/** Re-sends the account-setup email (INVITED only; at most once a minute per account). */
	public StaffAccount resendInvite(UUID id) {
		permissionCheckService.requireCapability(Capability.STAFF_MANAGE);
		Loaded loaded = load(id);
		requireStaffMembership(loaded.summary());
		userProvisioningApi.resendInvite(loaded.profile().getUserId());
		audit("staff.invite_resent", id, Map.of("email", ContactMasking.maskEmail(loaded.summary().email())));
		return loaded.account();
	}

	// ------------------------------------------------------------------ helpers

	private record Loaded(StaffProfile profile, TenantUserSummary summary, StaffAccount account) {

	}

	/** Allowed-actions inputs for the current actor, computed once per call. */
	private record Permissions(UUID actorUserId, boolean manage, boolean assignRoles) {

	}

	private Permissions currentPermissions() {
		return new Permissions(AuthenticatedPrincipalHolder.get().userId(),
				permissionCheckService.hasCapability(Capability.STAFF_MANAGE),
				permissionCheckService.hasCapability(Capability.STAFF_ASSIGN_ROLES));
	}

	/** Tenant-scoped by TenantAwareRepository: another tenant's id is structurally invisible (404). */
	private StaffProfile loadProfile(UUID id) {
		return staffProfileRepository.findById(id).orElseThrow(() -> new NotFoundException("Staff account not found"));
	}

	private Loaded load(UUID id) {
		StaffProfile profile = loadProfile(id);
		TenantUserSummary summary = summariesByUserId(List.of(profile)).get(profile.getUserId());
		if (summary == null) {
			throw new NotFoundException("Staff account not found");
		}
		Instant lastLogin = userProvisioningApi.findLastLoginAt(List.of(profile.getUserId())).get(profile.getUserId());
		StaffAccount account = toAccount(profile, summary, lastLogin, userProvisioningApi.roleDisplayNames(),
				currentPermissions());
		return new Loaded(profile, summary, account);
	}

	private void requireNotSelf(StaffProfile profile, String message) {
		if (profile.getUserId().equals(AuthenticatedPrincipalHolder.get().userId())) {
			throw new AccessDeniedException(message);
		}
	}

	private static void requireStaffMembership(TenantUserSummary summary) {
		if (PROTECTED_ROLES.contains(summary.roleCode())) {
			throw new AccessDeniedException("This account is not managed through Staff Management");
		}
	}

	private Map<UUID, TenantUserSummary> summariesByUserId(List<StaffProfile> profiles) {
		List<UUID> userIds = profiles.stream().map(StaffProfile::getUserId).toList();
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
	}

	private static StaffAccount toAccount(StaffProfile profile, TenantUserSummary summary, Instant lastLoginAt,
			Map<String, String> roleNames, Permissions perms) {
		String status = summary.status().toUpperCase(Locale.ROOT);
		boolean self = profile.getUserId().equals(perms.actorUserId());
		boolean staffMembership = !PROTECTED_ROLES.contains(summary.roleCode());
		boolean mutable = perms.manage() && staffMembership;
		StaffAccount.Actions actions = new StaffAccount.Actions(mutable,
				perms.assignRoles() && staffMembership && !self, mutable && !self && !"SUSPENDED".equals(status),
				mutable && !self && "SUSPENDED".equals(status), mutable && "INVITED".equals(status));
		return new StaffAccount(profile.getId(), profile.displayFirstName(), profile.displayLastName(),
				profile.getName(), summary.email(), profile.getContactNumber(), summary.roleCode(),
				roleNames.getOrDefault(summary.roleCode(), summary.roleCode()), status, lastLoginAt, actions);
	}

	private void audit(String action, UUID profileId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), action, TARGET, profileId,
				null, metadata));
	}

}
