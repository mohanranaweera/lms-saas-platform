package com.lms.usermanagement.staff.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.usermanagement.staff.StaffApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STAFF-MGMT-02 Staff Management through the real filter chain (Testcontainers): invite-only
 * creation, search, profile edit, role reassignment, deactivate/reactivate, resend invite,
 * forged-role / self / cross-tenant rejections and audit entries. Not {@code @Transactional}:
 * MockMvc requests commit independently.
 */
class StaffManagementIntegrationTest extends StaffApiTestSupport {

	// ------------------------------------------------------------------ create (A1)

	@Test
	void tenantAdminInvitesStaffWithoutAPasswordAndTheAccountIsInvited() {
		Fx fx = tenantWithAdmin("staff-invite");
		String email = uniqueEmail("new-staff");

		MvcResult raw = call(HttpMethod.POST, STAFF, fx.host(), fx.adminToken(), Map.of("firstName", " Nimal ",
				"lastName", "Perera", "email", email.toUpperCase(), "mobileNumber", "077 123 4567",
				"roleCode", "OPERATOR", "password", "ignored-Password-1!"));
		JsonNode created = expect(raw, 201).path("data");

		assertThat(created.path("firstName").asString()).isEqualTo("Nimal");
		assertThat(created.path("lastName").asString()).isEqualTo("Perera");
		assertThat(created.path("name").asString()).isEqualTo("Nimal Perera");
		assertThat(created.path("email").asString()).isEqualTo(email);
		assertThat(created.path("contactNumber").asString()).isEqualTo("077 123 4567");
		assertThat(created.path("roleCode").asString()).isEqualTo("OPERATOR");
		assertThat(created.path("roleName").asString()).isEqualTo("Operator");
		assertThat(created.path("status").asString()).isEqualTo("INVITED");
		assertThat(created.path("actions").path("canResendInvite").asBoolean()).isTrue();
		assertThat(content(raw).toLowerCase()).doesNotContain("password").doesNotContain("hash");

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT role, status FROM tenant_user WHERE tenant_id = ? AND email = ?", fx.tenantId(), email);
		assertThat(row.get("role")).isEqualTo("OPERATOR");
		assertThat(row.get("status")).isEqualTo("invited");
		// The typed password was never accepted: it cannot log in.
		assertThat(login(fx.host(), email, "ignored-Password-1!").getStatusCode().value()).isEqualTo(401);

		awaitOutbox(fx.tenantId(), "ACCOUNT_SETUP_INVITE", 1);
		String metadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? "
				+ "AND action = 'staff.invited'", String.class, fx.tenantId());
		assertThat(metadata).contains("OPERATOR").doesNotContain(email);

		JsonNode reopened = expect(call(HttpMethod.GET, STAFF + "/" + created.path("id").asString(), fx.host(),
				fx.adminToken(), null), 200).path("data");
		assertThat(reopened).isEqualTo(created);
	}

	@Test
	void duplicateEmailInTheSameTenantIs409WithAGenericMessageButAnotherTenantMayUseIt() {
		Fx a = tenantWithAdmin("staff-dup-a");
		Fx b = tenantWithAdmin("staff-dup-b");
		String email = uniqueEmail("dup");
		createStaff(a, "First", "One", email, "OPERATOR");

		JsonNode dup = expect(call(HttpMethod.POST, STAFF, a.host(), a.adminToken(), Map.of("firstName", "Second",
				"lastName", "Two", "email", email.toUpperCase(), "mobileNumber", "0771234567", "roleCode", "MANAGER")),
				409);
		assertThat(errorCode(dup)).isEqualTo("CONFLICT");
		assertThat(dup.path("error").path("message").asString())
			.isEqualTo("An account with this email already exists in this institute");
		// The admin's own email is also taken in this tenant.
		expect(call(HttpMethod.POST, STAFF, a.host(), a.adminToken(), Map.of("firstName", "X", "lastName", "Y",
				"email", "admin@example.test", "mobileNumber", "0771234567", "roleCode", "OPERATOR")), 409);

		createStaff(b, "Other", "Tenant", email, "OPERATOR");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE lower(email) = ?", Long.class,
				email)).isEqualTo(2L);
	}

	@Test
	void concurrentDoubleSubmitCreatesExactlyOneAccount() throws Exception {
		Fx fx = tenantWithAdmin("staff-race");
		String email = uniqueEmail("race");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					barrier.await();
					return call(HttpMethod.POST, STAFF, fx.host(), fx.adminToken(), Map.of("firstName", "Race",
							"lastName", "Staff", "email", email, "mobileNumber", "0771234567", "roleCode", "OPERATOR"));
				}));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<MvcResult> future : futures) {
				statuses.add(future.get(15, TimeUnit.SECONDS).getResponse().getStatus());
			}
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM staff_profile sp JOIN tenant_user tu "
					+ "ON tu.id = sp.user_id WHERE sp.tenant_id = ? AND tu.email = ?", Long.class, fx.tenantId(), email))
				.isEqualTo(1L);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void forgedLegacyOrAdminRolesAreRejectedWith400AndWriteNothing() {
		Fx fx = tenantWithAdmin("staff-forged");
		for (String role : List.of("TENANT_ADMIN", "PLATFORM_ADMIN", "STUDENT", "TEACHER", "FINANCE_STAFF",
				"READ_ONLY_AUDITOR", "operator", "BOGUS")) {
			String email = uniqueEmail("forged");
			JsonNode body = expect(call(HttpMethod.POST, STAFF, fx.host(), fx.adminToken(), Map.of("firstName", "F",
					"lastName", "R", "email", email, "mobileNumber", "0771234567", "roleCode", role)), 400);
			assertThat(errorCode(body)).as(role).isEqualTo("VALIDATION_ERROR");
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE email = ?", Long.class,
					email)).as(role).isZero();
		}
		assertThat(auditCount(fx.tenantId(), "staff.invited")).isZero();
	}

	@Test
	void assignableRolesAreOperatorAndManagerOnly() {
		Fx fx = tenantWithAdmin("staff-assignable");
		JsonNode roles = expect(call(HttpMethod.GET, STAFF + "/assignable-roles", fx.host(), fx.adminToken(), null),
				200).path("data");
		assertThat(roles).hasSize(2);
		assertThat(roles.get(0).path("code").asString()).isEqualTo("OPERATOR");
		assertThat(roles.get(0).path("displayName").asString()).isEqualTo("Operator");
		assertThat(roles.get(1).path("code").asString()).isEqualTo("MANAGER");
	}

	// ------------------------------------------------------------------ list / search

	@Test
	void listSearchesNameEmailAndPhoneSortsByLastNameAndShowsLegacyRows() {
		Fx fx = tenantWithAdmin("staff-search");
		createStaff(fx, "Amal", "Zoysa", uniqueEmail("amal"), "OPERATOR");
		createStaff(fx, "Kamal", "Bandara", uniqueEmail("kamal-unique-mail"), "MANAGER");
		UUID legacyUser = seedTenantUser(fx.tenantId(), uniqueEmail("legacy"), RAW_PASSWORD, Role.FINANCE_STAFF)
			.getId();
		jdbcTemplate.update("INSERT INTO staff_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Legacy Old Staff', now(), now())", UUID.randomUUID(), fx.tenantId(), legacyUser);

		JsonNode all = list(fx, "");
		assertThat(all.path("totalElements").asLong()).isEqualTo(3);
		List<String> lastNames = new ArrayList<>();
		all.path("content").forEach(n -> lastNames.add(n.path("lastName").asString()));
		assertThat(lastNames).containsExactly("Bandara", "Zoysa", "Old Staff");
		JsonNode legacy = all.path("content").get(2);
		assertThat(legacy.path("firstName").asString()).isEqualTo("Legacy");
		assertThat(legacy.path("roleCode").asString()).isEqualTo("FINANCE_STAFF");
		assertThat(legacy.path("roleName").asString()).isEqualTo("Finance Staff");
		assertThat(legacy.path("status").asString()).isEqualTo("ACTIVE");

		assertThat(list(fx, "?q=zoy").path("content").get(0).path("firstName").asString()).isEqualTo("Amal");
		assertThat(list(fx, "?q=KAMAL-UNIQUE").path("totalElements").asLong()).isEqualTo(1);
		assertThat(list(fx, "?q=0771234").path("totalElements").asLong()).isEqualTo(2);
		assertThat(list(fx, "?q=nobody-matches").path("totalElements").asLong()).isZero();
		JsonNode paged = list(fx, "?size=500&page=0");
		assertThat(paged.path("size").asInt()).isEqualTo(100);
		assertThat(list(fx, "?size=1&page=1").path("content").get(0).path("lastName").asString()).isEqualTo("Zoysa");
	}

	// ------------------------------------------------------------------ edit (A2) / role (A3)

	@Test
	void profilePatchUpdatesOnlyProfileFieldsAndIsAudited() {
		Fx fx = tenantWithAdmin("staff-patch");
		JsonNode created = createStaff(fx, "Old", "Name", uniqueEmail("patch"), "OPERATOR");
		String id = created.path("id").asString();

		JsonNode updated = expect(call(HttpMethod.PATCH, STAFF + "/" + id, fx.host(), fx.adminToken(),
				Map.of("firstName", "New", "lastName", "Person", "contactNumber", "+94 11 222 3333", "roleCode",
						"TENANT_ADMIN", "email", "hijack@example.test")),
				200).path("data");

		assertThat(updated.path("name").asString()).isEqualTo("New Person");
		assertThat(updated.path("contactNumber").asString()).isEqualTo("+94 11 222 3333");
		assertThat(updated.path("roleCode").asString()).isEqualTo("OPERATOR");
		assertThat(updated.path("email").asString()).isEqualTo(created.path("email").asString());
		assertThat(auditCount(fx.tenantId(), "staff.profile_updated")).isEqualTo(1);
	}

	@Test
	void roleReassignmentWorksForStaffAndLegacyMembersButNeverSelfOrProtectedMemberships() {
		Fx fx = tenantWithAdmin("staff-role");
		String id = createStaff(fx, "Role", "Change", uniqueEmail("role"), "OPERATOR").path("id").asString();

		JsonNode changed = expect(call(HttpMethod.PUT, STAFF + "/" + id + "/role", fx.host(), fx.adminToken(),
				Map.of("roleCode", "MANAGER")), 200).path("data");
		assertThat(changed.path("roleCode").asString()).isEqualTo("MANAGER");
		String metadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? "
				+ "AND action = 'staff.role_changed'", String.class, fx.tenantId());
		assertThat(metadata).contains("\"from\"").contains("OPERATOR").contains("MANAGER");

		// Forged target roles.
		for (String role : List.of("TENANT_ADMIN", "FINANCE_STAFF", "STUDENT")) {
			expect(call(HttpMethod.PUT, STAFF + "/" + id + "/role", fx.host(), fx.adminToken(),
					Map.of("roleCode", role)), 400);
		}

		// Legacy sub-role member may be moved to Operator.
		UUID legacyUser = seedTenantUser(fx.tenantId(), uniqueEmail("legacy-role"), RAW_PASSWORD, Role.EXAM_MANAGER)
			.getId();
		UUID legacyProfile = seedProfile(fx.tenantId(), legacyUser, "Legacy Exam");
		expect(call(HttpMethod.PUT, STAFF + "/" + legacyProfile + "/role", fx.host(), fx.adminToken(),
				Map.of("roleCode", "OPERATOR")), 200);

		// A teacher membership that somehow has a staff profile is untouchable.
		UUID teacherUser = seedTenantUser(fx.tenantId(), uniqueEmail("teacher"), RAW_PASSWORD, Role.TEACHER).getId();
		UUID teacherProfile = seedProfile(fx.tenantId(), teacherUser, "Some Teacher");
		expect(call(HttpMethod.PUT, STAFF + "/" + teacherProfile + "/role", fx.host(), fx.adminToken(),
				Map.of("roleCode", "OPERATOR")), 403);
		assertThat(jdbcTemplate.queryForObject("SELECT role FROM tenant_user WHERE id = ?", String.class, teacherUser))
			.isEqualTo("TEACHER");
		// A sign-in mobile is a login credential (ADR-031): same protection.
		expect(call(HttpMethod.PUT, STAFF + "/" + teacherProfile + "/login-mobile", fx.host(), fx.adminToken(),
				Map.of("loginMobile", "+94771234567")), 403);

		// Self: the admin's own account (given a profile) can neither change role nor be deactivated.
		UUID selfProfile = seedProfile(fx.tenantId(), fx.adminId(), "The Admin");
		expect(call(HttpMethod.PUT, STAFF + "/" + selfProfile + "/role", fx.host(), fx.adminToken(),
				Map.of("roleCode", "OPERATOR")), 403);
		expect(call(HttpMethod.POST, STAFF + "/" + selfProfile + "/deactivate", fx.host(), fx.adminToken(), null), 403);
		expect(call(HttpMethod.PUT, STAFF + "/" + selfProfile + "/login-mobile", fx.host(), fx.adminToken(),
				Map.of("loginMobile", "+94771234568")), 403);
		assertThat(jdbcTemplate.queryForObject("SELECT role || '/' || status FROM tenant_user WHERE id = ?",
				String.class, fx.adminId())).isEqualTo("TENANT_ADMIN/active");
	}

	@Test
	void roleChangeAppliesOnTheMembersNextRequest() {
		Fx fx = tenantWithAdmin("staff-role-live");
		String email = uniqueEmail("live");
		String memberToken = userToken(fx, email, Role.READ_ONLY_AUDITOR);
		UUID userId = jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?",
				UUID.class, fx.tenantId(), email);
		UUID profile = seedProfile(fx.tenantId(), userId, "Live Member");
		expect(call(HttpMethod.GET, STAFF, fx.host(), memberToken, null), 200);

		expect(call(HttpMethod.PUT, STAFF + "/" + profile + "/role", fx.host(), fx.adminToken(),
				Map.of("roleCode", "OPERATOR")), 200);

		expect(call(HttpMethod.GET, STAFF, fx.host(), memberToken, null), 403);
		assertThat(expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), memberToken, null), 200)
			.path("data").path("role").asString()).isEqualTo("OPERATOR");
	}

	// ------------------------------------------------------------------ deactivate / reactivate (A4)

	@Test
	@Tag("cross-tenant")
	void deactivateSuspendsAndRevokesSessionsWithoutTouchingTheSameEmailInAnotherTenant() {
		Fx a = tenantWithAdmin("staff-deact-a");
		Fx b = tenantWithAdmin("staff-deact-b");
		String email = uniqueEmail("shared");
		String tokenA = userToken(a, email, Role.OPERATOR);
		String tokenB = userToken(b, email, Role.OPERATOR);
		UUID userA = jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?",
				UUID.class, a.tenantId(), email);
		UUID profileA = seedProfile(a.tenantId(), userA, "Shared Person");
		expect(call(HttpMethod.GET, "/api/v1/me/permissions", a.host(), tokenA, null), 200);

		JsonNode deactivated = expect(call(HttpMethod.POST, STAFF + "/" + profileA + "/deactivate", a.host(),
				a.adminToken(), null), 200).path("data");
		assertThat(deactivated.path("status").asString()).isEqualTo("SUSPENDED");
		assertThat(deactivated.path("actions").path("canReactivate").asBoolean()).isTrue();
		assertThat(call(HttpMethod.GET, "/api/v1/me/permissions", a.host(), tokenA, null).getResponse().getStatus())
			.isIn(401, 403);
		assertThat(auditCount(a.tenantId(), "staff.deactivated")).isEqualTo(1);

		// Tenant B's membership of the same person is untouched.
		expect(call(HttpMethod.GET, "/api/v1/me/permissions", b.host(), tokenB, null), 200);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?",
				String.class, b.tenantId(), email)).isEqualTo("active");
		assertThat(auditCount(b.tenantId(), "staff.deactivated")).isZero();

		JsonNode reactivated = expect(call(HttpMethod.POST, STAFF + "/" + profileA + "/reactivate", a.host(),
				a.adminToken(), null), 200).path("data");
		assertThat(reactivated.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(auditCount(a.tenantId(), "staff.reactivated")).isEqualTo(1);
		token(a.host(), email);
	}

	@Test
	void reactivatingAnInviteeThatNeverSetAPasswordReturnsItToInvited() {
		Fx fx = tenantWithAdmin("staff-reinvite");
		String email = uniqueEmail("invitee");
		String id = createStaff(fx, "In", "Vitee", email, "OPERATOR").path("id").asString();
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?",
				String.class, fx.tenantId(), email)).isEqualTo("invited");

		expect(call(HttpMethod.POST, STAFF + "/" + id + "/deactivate", fx.host(), fx.adminToken(), null), 200);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?",
				String.class, fx.tenantId(), email)).isEqualTo("suspended");

		JsonNode reactivated = expect(call(HttpMethod.POST, STAFF + "/" + id + "/reactivate", fx.host(),
				fx.adminToken(), null), 200).path("data");
		assertThat(reactivated.path("status").asString()).isEqualTo("INVITED");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?",
				String.class, fx.tenantId(), email)).isEqualTo("invited");
		assertThat(auditCount(fx.tenantId(), "staff.reactivated")).isEqualTo(1);
		// Still an invitation: the setup email can be re-sent, and nobody can sign in with a password.
		expect(call(HttpMethod.POST, STAFF + "/" + id + "/resend-invite", fx.host(), fx.adminToken(), null), 200);
		assertThat(login(fx.host(), email, RAW_PASSWORD).getStatusCode().is2xxSuccessful()).isFalse();
	}

	// ------------------------------------------------------------------ resend invite

	@Test
	void resendInviteWorksForInvitedOnlyAndIsRateLimited() {
		Fx fx = tenantWithAdmin("staff-resend");
		String id = createStaff(fx, "Re", "Send", uniqueEmail("resend"), "MANAGER").path("id").asString();

		expect(call(HttpMethod.POST, STAFF + "/" + id + "/resend-invite", fx.host(), fx.adminToken(), null), 200);
		expect(call(HttpMethod.POST, STAFF + "/" + id + "/resend-invite", fx.host(), fx.adminToken(), null), 429);
		assertThat(auditCount(fx.tenantId(), "staff.invite_resent")).isEqualTo(1);

		UUID active = seedTenantUser(fx.tenantId(), uniqueEmail("active"), RAW_PASSWORD, Role.OPERATOR).getId();
		UUID activeProfile = seedProfile(fx.tenantId(), active, "Already Active");
		expect(call(HttpMethod.POST, STAFF + "/" + activeProfile + "/resend-invite", fx.host(), fx.adminToken(), null),
				409);
	}

	// ------------------------------------------------------------------ isolation & authorization (A5)

	@Test
	@Tag("cross-tenant")
	void anotherTenantsStaffIdIs404OnEveryEndpointAndNeverListed() {
		Fx a = tenantWithAdmin("staff-x-a");
		Fx b = tenantWithAdmin("staff-x-b");
		String idA = createStaff(a, "Tenant", "Ayes", uniqueEmail("tenant-a"), "OPERATOR").path("id").asString();

		expect(call(HttpMethod.GET, STAFF + "/" + idA, b.host(), b.adminToken(), null), 404);
		expect(call(HttpMethod.PATCH, STAFF + "/" + idA, b.host(), b.adminToken(),
				Map.of("firstName", "Hijack", "lastName", "X", "contactNumber", "")), 404);
		expect(call(HttpMethod.PUT, STAFF + "/" + idA + "/role", b.host(), b.adminToken(), Map.of("roleCode",
				"MANAGER")), 404);
		expect(call(HttpMethod.POST, STAFF + "/" + idA + "/deactivate", b.host(), b.adminToken(), null), 404);
		expect(call(HttpMethod.POST, STAFF + "/" + idA + "/reactivate", b.host(), b.adminToken(), null), 404);
		expect(call(HttpMethod.POST, STAFF + "/" + idA + "/resend-invite", b.host(), b.adminToken(), null), 404);
		expect(call(HttpMethod.GET, STAFF + "/" + idA + "/login-mobile", b.host(), b.adminToken(), null), 404);
		assertThat(list(b, "").path("totalElements").asLong()).isZero();

		JsonNode stillA = expect(call(HttpMethod.GET, STAFF + "/" + idA, a.host(), a.adminToken(), null), 200)
			.path("data");
		assertThat(stillA.path("firstName").asString()).isEqualTo("Tenant");
		assertThat(stillA.path("roleCode").asString()).isEqualTo("OPERATOR");
		assertThat(stillA.path("status").asString()).isEqualTo("INVITED");
	}

	@Test
	void operatorsManagersAndLegacyStaffCannotManageStaffAndTheAuditorIsReadOnly() {
		Fx fx = tenantWithAdmin("staff-deny");
		String existing = createStaff(fx, "Exist", "Ing", uniqueEmail("exist"), "OPERATOR").path("id").asString();
		for (Role role : List.of(Role.OPERATOR, Role.MANAGER, Role.FINANCE_STAFF, Role.TEACHER, Role.STUDENT)) {
			String token = userToken(fx, uniqueEmail(role.name().toLowerCase()), role);
			expect(call(HttpMethod.GET, STAFF, fx.host(), token, null), 403);
			expect(call(HttpMethod.GET, STAFF + "/" + existing, fx.host(), token, null), 403);
			expect(call(HttpMethod.POST, STAFF, fx.host(), token, Map.of("firstName", "A", "lastName", "B", "email",
					uniqueEmail("blocked"), "mobileNumber", "0771234567", "roleCode", "OPERATOR")), 403);
			expect(call(HttpMethod.POST, STAFF + "/" + existing + "/deactivate", fx.host(), token, null), 403);
			expect(call(HttpMethod.GET, MATRIX, fx.host(), token, null), 403);
		}

		String auditor = userToken(fx, uniqueEmail("auditor"), Role.READ_ONLY_AUDITOR);
		JsonNode row = expect(call(HttpMethod.GET, STAFF + "/" + existing, fx.host(), auditor, null), 200).path("data");
		assertThat(row.path("actions").path("canEdit").asBoolean()).isFalse();
		assertThat(row.path("actions").path("canChangeRole").asBoolean()).isFalse();
		assertThat(row.path("actions").path("canDeactivate").asBoolean()).isFalse();
		expect(call(HttpMethod.POST, STAFF, fx.host(), auditor, Map.of("firstName", "A", "lastName", "B", "email",
				uniqueEmail("blocked"), "mobileNumber", "0771234567", "roleCode", "OPERATOR")), 403);
		expect(call(HttpMethod.PUT, STAFF + "/" + existing + "/role", fx.host(), auditor,
				Map.of("roleCode", "MANAGER")), 403);
	}

	// ------------------------------------------------------------------ helpers

	private JsonNode list(Fx fx, String query) {
		return expect(call(HttpMethod.GET, STAFF + query, fx.host(), fx.adminToken(), null), 200).path("data");
	}

	private UUID seedProfile(UUID tenantId, UUID userId, String name) {
		UUID id = UUID.randomUUID();
		String[] parts = name.split(" ", 2);
		jdbcTemplate.update("INSERT INTO staff_profile (id, tenant_id, user_id, name, first_name, last_name, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())", id, tenantId, userId, name,
				parts[0], parts.length > 1 ? parts[1] : null);
		return id;
	}

	private void awaitOutbox(UUID tenantId, String eventType, int expected) {
		for (int i = 0; i < 50; i++) {
			Long count = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND event_type = ?", Long.class,
					tenantId, eventType);
			if (count != null && count >= expected) {
				return;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new AssertionError("No " + eventType + " outbox row was queued");
	}

}
