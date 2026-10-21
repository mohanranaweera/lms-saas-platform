package com.lms.integrationmanagement.media;

import static com.lms.integrationmanagement.media.FakeMediaProviderServer.BUNNY_LIBRARY_SECRET_PREFIX;
import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_BUNNY_ACCOUNT_KEY;
import static com.lms.integrationmanagement.media.FakeMediaProviderServer.OTHER_BUNNY_ACCOUNT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.api.BunnyLibraryApi;
import com.lms.integrationmanagement.api.BunnyLibraryApi.TeacherLibrarySummary;
import com.lms.integrationmanagement.service.BunnyLibraryStore;
import com.lms.tenantmanagement.domain.Tenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * MEDIA-INT-01A: one Bunny Stream library per teacher inside the tenant's
 * Bunny account - idempotent creation, adoption after an interrupted attempt,
 * failure/retry, the concurrency guard, availability after the account key
 * changes, permissions, tenant isolation and the V61 constraints.
 */
class BunnyTeacherLibraryIntegrationTest extends MediaIntegrationTestSupport {

	private static final String TEACHER_NAME = "Jane Perera";

	@Autowired
	private BunnyLibraryApi bunnyLibraryApi;

	@Autowired
	private BunnyLibraryStore libraryStore;

	private record Ctx(Tenant tenant, String host, String adminToken, TenantUser admin, TenantUser teacher,
			String teacherToken) {
	}

	private Ctx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(tenant.getId(), teacher.getId(), TEACHER_NAME);
		String host = hostFor(tenant.getSubdomain());
		return new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"), admin, teacher,
				loginAndGetToken(host, "teacher@example.test"));
	}

	private UUID connectBunny(Ctx ctx, String accountKey) {
		MvcResult saved = adminPut(ctx.host(), ctx.adminToken(), "/bunny", Map.of("apiKey", accountKey));
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
		return UUID.fromString(json(saved).path("data").path("id").asString());
	}

	private static String tagOf(UUID teacherUserId) {
		String hex = teacherUserId.toString().replace("-", "");
		return hex.substring(hex.length() - 12);
	}

	private String rowStatus(UUID rowId) {
		return jdbcTemplate.queryForObject("SELECT status FROM bunny_teacher_library WHERE id = ?", String.class,
				rowId);
	}

	private int auditCount(String action, UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND tenant_id = ?",
				Integer.class, action, tenantId);
	}

	private UUID insertLibraryRow(Ctx ctx, UUID connectionId, String status, Long bunnyId, Instant createdAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO bunny_teacher_library
				    (id, tenant_id, teacher_user_id, connection_id, bunny_library_id, library_name, status,
				     created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'Seeded', ?, ?, ?)
				""", id, ctx.tenant().getId(), ctx.teacher().getId(), connectionId, bunnyId, status,
				Timestamp.from(createdAt), Timestamp.from(createdAt));
		return id;
	}

	private String libraryStatusInAdminList(Ctx ctx) {
		return json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data")
			.path(0)
			.path("library")
			.path("status")
			.asString();
	}

	// ------------------------------------------------------------------
	// Creation
	// ------------------------------------------------------------------

	@Test
	void adminCreatesATeachersLibraryOnceAndTheTeacherSeesIt() {
		Ctx ctx = seed("bl-create");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);

		MvcResult created = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		JsonNode library = json(created).path("data");
		assertThat(library.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(library.path("libraryName").asString()).contains(TEACHER_NAME)
			.endsWith("[" + tagOf(ctx.teacher().getId()) + "]");
		// No library key, account key or Bunny numeric id in the response.
		assertThat(rawContent(created)).doesNotContain(BUNNY_LIBRARY_SECRET_PREFIX)
			.doesNotContain(GOOD_BUNNY_ACCOUNT_KEY)
			.doesNotContain("bunnyLibraryId");
		assertThat(PROVIDERS.requests("POST", "/videolibrary")).hasSize(1);
		assertThat(auditCount("media_integration.bunny_library_created", ctx.tenant().getId())).isEqualTo(1);

		// Idempotent: the same library, no second Bunny create.
		MvcResult again = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(again)).isEqualTo(200);
		assertThat(json(again).path("data").path("id").asString()).isEqualTo(library.path("id").asString());
		assertThat(PROVIDERS.requests("POST", "/videolibrary")).hasSize(1);
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isEqualTo(1);

		JsonNode rows = json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data");
		assertThat(rows.size()).isEqualTo(1);
		assertThat(rows.path(0).path("teacherUserId").asString()).isEqualTo(ctx.teacher().getId().toString());
		assertThat(rows.path(0).path("library").path("status").asString()).isEqualTo("ACTIVE");
		assertThat(rows.path(0).path("canCreateLibrary").asBoolean()).isFalse();

		JsonNode overview = json(teacherOverview(ctx.host(), ctx.teacherToken())).path("data");
		assertThat(overview.path("bunnyConnected").asBoolean()).isTrue();
		assertThat(overview.path("bunnyLibrary").path("status").asString()).isEqualTo("ACTIVE");
		assertThat(overview.path("bunnyLibrary").path("libraryName").asString()).contains(TEACHER_NAME);
	}

	@Test
	void teachersWithoutALibraryAreListedAndTheTeacherCardIsEmpty() {
		Ctx ctx = seed("bl-empty");

		JsonNode overview = json(teacherOverview(ctx.host(), ctx.teacherToken())).path("data");
		assertThat(overview.path("bunnyConnected").asBoolean()).isFalse();
		assertThat(overview.path("bunnyLibrary").isNull()).isTrue();

		JsonNode rows = json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data");
		assertThat(rows.size()).isEqualTo(1);
		assertThat(rows.path(0).path("teacherName").asString()).isEqualTo(TEACHER_NAME);
		assertThat(rows.path(0).path("library").isNull()).isTrue();
		// Not connected yet - nothing can be created.
		assertThat(rows.path(0).path("canCreateLibrary").asBoolean()).isFalse();

		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		assertThat(json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data").path(0)
			.path("canCreateLibrary")
			.asBoolean()).isTrue();
	}

	@Test
	void withoutAnActiveBunnyAccountNothingIsCreated() {
		Ctx ctx = seed("bl-noconn");

		MvcResult result = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(result)).isEqualTo(409);
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isZero();
		assertThat(PROVIDERS.requests("POST", "/videolibrary")).isEmpty();
	}

	@Test
	void onlyTeachersOfThisTenantCanGetALibrary() {
		Ctx ctx = seed("bl-notteacher");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);

		assertThat(status(adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.admin().getId()))).isEqualTo(404);
		assertThat(status(adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), UUID.randomUUID()))).isEqualTo(404);
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void aLibraryLeftByAnInterruptedAttemptIsAdoptedNotDuplicated() {
		Ctx ctx = seed("bl-adopt");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		PROVIDERS.addBunnyLibrary(GOOD_BUNNY_ACCOUNT_KEY, "Old name [" + tagOf(ctx.teacher().getId()) + "]");

		MvcResult result = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(result)).isEqualTo(201);
		assertThat(json(result).path("data").path("libraryName").asString()).startsWith("Old name");
		assertThat(PROVIDERS.requests("POST", "/videolibrary")).isEmpty();
		assertThat(PROVIDERS.bunnyLibraries()).hasSize(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT metadata->>'adopted' FROM audit_log WHERE action = 'media_integration.bunny_library_created'"
						+ " AND tenant_id = ?",
				String.class, ctx.tenant().getId()))
			.isEqualTo("true");
	}

	@Test
	void aBunnyFailureLeavesAFailedRowAndARetrySucceeds() {
		Ctx ctx = seed("bl-retry");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		PROVIDERS.setFailBunnyCreate(true);

		MvcResult failed = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(failed)).isEqualTo(503);
		assertThat(auditCount("media_integration.bunny_library_failed", ctx.tenant().getId())).isEqualTo(1);
		JsonNode row = json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data").path(0).path("library");
		assertThat(row.path("status").asString()).isEqualTo("FAILED");
		assertThat(row.path("lastError").asString()).isNotBlank();

		PROVIDERS.setFailBunnyCreate(false);
		MvcResult retried = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(retried)).isEqualTo(201);
		assertThat(json(retried).path("data").path("status").asString()).isEqualTo("ACTIVE");
		// The failed attempt stays as history.
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isEqualTo(2);
	}

	@Test
	void anInProgressSetupBlocksASecondRequestButAStaleOneIsTakenOver() {
		Ctx ctx = seed("bl-race");
		UUID connectionId = connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		UUID inProgress = insertLibraryRow(ctx, connectionId, "PROVISIONING", null, Instant.now());

		MvcResult blocked = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(blocked)).isEqualTo(409);
		assertThat(PROVIDERS.requests("POST", "/videolibrary")).isEmpty();
		JsonNode fresh = json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data").path(0);
		assertThat(fresh.path("library").path("status").asString()).isEqualTo("PROVISIONING");
		assertThat(fresh.path("canCreateLibrary").asBoolean()).isFalse();

		jdbcTemplate.update("UPDATE bunny_teacher_library SET created_at = ? WHERE id = ?",
				Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), inProgress);
		// UI/UX review UX-1: an interrupted setup is offered for retry.
		assertThat(json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data").path(0)
			.path("canCreateLibrary")
			.asBoolean()).isTrue();
		MvcResult takenOver = adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		assertThat(status(takenOver)).isEqualTo(201);
		assertThat(rowStatus(inProgress)).isEqualTo("FAILED");
	}

	// ------------------------------------------------------------------
	// Availability after the account changes
	// ------------------------------------------------------------------

	@Test
	void aLibraryTheNewAccountKeyCannotSeeIsUnavailableButUntouched() {
		Ctx ctx = seed("bl-replaced");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		UUID rowId = UUID.fromString(json(adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId()))
			.path("data")
			.path("id")
			.asString());

		// Same Bunny account under a new connection row: still visible -> ACTIVE.
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		assertThat(libraryStatusInAdminList(ctx)).isEqualTo("ACTIVE");

		// A different Bunny account: the library is not visible any more.
		connectBunny(ctx, OTHER_BUNNY_ACCOUNT_KEY);
		assertThat(libraryStatusInAdminList(ctx)).isEqualTo("UNAVAILABLE");
		assertThat(json(teacherOverview(ctx.host(), ctx.teacherToken())).path("data").path("bunnyLibrary")
			.path("status")
			.asString()).isEqualTo("UNAVAILABLE");
		assertThat(rowStatus(rowId)).isEqualTo("ACTIVE");
	}

	@Test
	void librariesShowUnavailableWhileBunnyIsDisconnected() {
		Ctx ctx = seed("bl-disconnected");
		UUID connectionId = connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());

		assertThat(status(adminDisconnect(ctx.host(), ctx.adminToken(), connectionId, null))).isEqualTo(200);

		assertThat(libraryStatusInAdminList(ctx)).isEqualTo("UNAVAILABLE");
		assertThat(json(teacherOverview(ctx.host(), ctx.teacherToken())).path("data").path("bunnyConnected")
			.asBoolean()).isFalse();
	}

	@Test
	void theAvailabilityCheckIsOneBunnyListingNotOneCallPerLibrary() {
		Ctx ctx = seed("bl-onecall");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		for (int i = 1; i <= 3; i++) {
			TenantUser teacher = seedTenantUser(ctx.tenant().getId(), "t" + i + "@example.test", RAW_PASSWORD,
					Role.TEACHER);
			seedTeacherProfile(ctx.tenant().getId(), teacher.getId(), "Teacher " + i);
			assertThat(status(adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), teacher.getId()))).isEqualTo(201);
		}
		connectBunny(ctx, OTHER_BUNNY_ACCOUNT_KEY);
		PROVIDERS.reset();

		JsonNode rows = json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data");

		assertThat(rows.findValuesAsString("status")).contains("UNAVAILABLE");
		// Security review M-1: one listing call, never a GET per library.
		assertThat(PROVIDERS.requests("GET", "/videolibrary")).hasSize(1);
		assertThat(PROVIDERS.requests("GET", "/videolibrary/")).isEmpty();
	}

	@Test
	void anAlreadyTakenOverStaleRowIsLeftAloneInsteadOfFailing() {
		Ctx ctx = seed("bl-takeover");
		UUID connectionId = connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		UUID row = insertLibraryRow(ctx, connectionId, "FAILED", null, Instant.now().minus(10, ChronoUnit.MINUTES));

		TenantContextHolder.set(ctx.tenant().getId());
		try {
			// Security review L-1: the losing side of a concurrent takeover must not throw.
			assertThat(libraryStore.failIfProvisioning(row, "interrupted", ctx.admin().getId())).isFalse();
		}
		finally {
			TenantContextHolder.clear();
		}
		assertThat(auditCount("media_integration.bunny_library_failed", ctx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Eligible teachers (security review L-3)
	// ------------------------------------------------------------------

	@Test
	void onlyApprovedActiveTeachersCanGetALibrary() {
		Ctx ctx = seed("bl-eligible");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		TenantUser pending = seedTenantUser(ctx.tenant().getId(), "pending@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(ctx.tenant().getId(), pending.getId(), "Pending Teacher");
		jdbcTemplate.update("UPDATE teacher_profile SET approval_status = 'PENDING' WHERE user_id = ?", pending.getId());

		assertThat(status(adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), pending.getId()))).isEqualTo(404);

		// The approved teacher gets a library, then is suspended: still listed, but no create action.
		adminCreateBunnyLibrary(ctx.host(), ctx.adminToken(), ctx.teacher().getId());
		jdbcTemplate.update("UPDATE tenant_user SET status = 'suspended' WHERE id = ?", ctx.teacher().getId());

		JsonNode rows = json(adminBunnyLibraries(ctx.host(), ctx.adminToken())).path("data");
		assertThat(rows.size()).isEqualTo(1);
		assertThat(rows.path(0).path("teacherUserId").asString()).isEqualTo(ctx.teacher().getId().toString());
		assertThat(rows.path(0).path("library").path("status").asString()).isEqualTo("ACTIVE");
		assertThat(rows.path(0).path("canCreateLibrary").asBoolean()).isFalse();
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// BunnyLibraryApi - the MEDIA-INT-03 hook (security review L-2)
	// ------------------------------------------------------------------

	private TeacherLibrarySummary ensureAs(Ctx ctx, TenantUser caller, String role, UUID teacherUserId) {
		TenantContextHolder.set(ctx.tenant().getId());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(caller.getId(), ctx.tenant().getId(), role,
				UUID.randomUUID()));
		try {
			return bunnyLibraryApi.ensureTeacherLibrary(teacherUserId);
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
			TenantContextHolder.clear();
		}
	}

	@Test
	void theApiLetsATeacherEnsureOnlyTheirOwnLibrary() {
		Ctx ctx = seed("bl-api-self");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		TenantUser other = seedTenantUser(ctx.tenant().getId(), "other@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(ctx.tenant().getId(), other.getId(), "Other Teacher");

		TeacherLibrarySummary own = ensureAs(ctx, ctx.teacher(), "TEACHER", ctx.teacher().getId());
		assertThat(own.status()).isEqualTo("ACTIVE");
		assertThat(own.libraryName()).contains(TEACHER_NAME);

		assertThatThrownBy(() -> ensureAs(ctx, ctx.teacher(), "TEACHER", other.getId()))
			.isInstanceOf(AccessDeniedException.class);
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isEqualTo(1);
	}

	@Test
	void theApiLetsAnAdminEnsureAnyTeachersLibrary() {
		Ctx ctx = seed("bl-api-admin");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);

		TeacherLibrarySummary created = ensureAs(ctx, ctx.admin(), "TENANT_ADMIN", ctx.teacher().getId());
		TeacherLibrarySummary again = ensureAs(ctx, ctx.admin(), "TENANT_ADMIN", ctx.teacher().getId());

		assertThat(created.status()).isEqualTo("ACTIVE");
		assertThat(again.id()).isEqualTo(created.id());
		assertThat(PROVIDERS.requests("POST", "/videolibrary")).hasSize(1);
	}

	// ------------------------------------------------------------------
	// Permissions & tenant isolation
	// ------------------------------------------------------------------

	@Test
	void readOnlyRolesCanListButNotCreateAndTeachersCannotUseTheAdminEndpoints() {
		Ctx ctx = seed("bl-perms");
		connectBunny(ctx, GOOD_BUNNY_ACCOUNT_KEY);
		seedTenantUser(ctx.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditor = loginAndGetToken(ctx.host(), "auditor@example.test");

		assertThat(status(adminBunnyLibraries(ctx.host(), auditor))).isEqualTo(200);
		assertThat(status(adminCreateBunnyLibrary(ctx.host(), auditor, ctx.teacher().getId()))).isEqualTo(403);
		assertThat(status(adminBunnyLibraries(ctx.host(), ctx.teacherToken()))).isEqualTo(403);
		assertThat(status(adminCreateBunnyLibrary(ctx.host(), ctx.teacherToken(), ctx.teacher().getId())))
			.isEqualTo(403);
		assertThat(bunnyLibraryRowCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void librariesAreIsolatedBetweenTenants() {
		Ctx tenantA = seed("bl-iso-a");
		connectBunny(tenantA, GOOD_BUNNY_ACCOUNT_KEY);
		adminCreateBunnyLibrary(tenantA.host(), tenantA.adminToken(), tenantA.teacher().getId());
		Ctx tenantB = seed("bl-iso-b");
		connectBunny(tenantB, OTHER_BUNNY_ACCOUNT_KEY);
		int requestsBefore = PROVIDERS.requests().size();

		// B's admin cannot create a library for A's teacher - and Bunny is never called.
		MvcResult crossTenant = adminCreateBunnyLibrary(tenantB.host(), tenantB.adminToken(),
				tenantA.teacher().getId());
		assertThat(status(crossTenant)).isEqualTo(404);
		assertThat(PROVIDERS.requests()).hasSize(requestsBefore);

		// B sees only its own teacher, with no library.
		JsonNode rowsB = json(adminBunnyLibraries(tenantB.host(), tenantB.adminToken())).path("data");
		assertThat(rowsB.size()).isEqualTo(1);
		assertThat(rowsB.path(0).path("teacherUserId").asString()).isEqualTo(tenantB.teacher().getId().toString());
		assertThat(rowsB.path(0).path("library").isNull()).isTrue();
		assertThat(json(teacherOverview(tenantB.host(), tenantB.teacherToken())).path("data").path("bunnyLibrary")
			.isNull()).isTrue();

		assertThat(bunnyLibraryRowCount(tenantA.tenant().getId())).isEqualTo(1);
		assertThat(bunnyLibraryRowCount(tenantB.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// V61 constraints
	// ------------------------------------------------------------------

	@Test
	void theSchemaEnforcesOneLiveLibraryPerTeacherAndSameTenantReferences() {
		Ctx a = seed("bl-schema-a");
		UUID connectionA = connectBunny(a, GOOD_BUNNY_ACCOUNT_KEY);
		Ctx b = seed("bl-schema-b");
		UUID connectionB = connectBunny(b, OTHER_BUNNY_ACCOUNT_KEY);

		insertLibraryRow(a, connectionA, "ACTIVE", 1L, Instant.now());
		// A second live row for the same teacher.
		assertThatThrownBy(() -> insertLibraryRow(a, connectionA, "PROVISIONING", null, Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
		// FAILED history rows are unlimited.
		insertLibraryRow(a, connectionA, "FAILED", null, Instant.now());
		insertLibraryRow(a, connectionA, "FAILED", null, Instant.now());
		// ACTIVE needs a Bunny library id.
		assertThatThrownBy(() -> insertLibraryRow(b, connectionB, "ACTIVE", null, Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
		// Tenant B's row cannot point at tenant A's connection...
		assertThatThrownBy(() -> insertLibraryRow(b, connectionA, "FAILED", null, Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
		// ...nor can tenant A's row name tenant B's teacher.
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO bunny_teacher_library
				    (id, tenant_id, teacher_user_id, connection_id, library_name, status, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'x', 'FAILED', now(), now())
				""", UUID.randomUUID(), a.tenant().getId(), b.teacher().getId(), connectionA))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
