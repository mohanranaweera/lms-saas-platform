package com.lms.identityaccessservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.NotificationManagementTestSupport;
import com.lms.notificationmanagement.service.NotificationDispatchService;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * AUTH-PWD-01 / ADR-020 end to end: first Tenant Admin invitation on approval,
 * account setup and password reset through one-time links, change password,
 * forced password change, and tenant isolation. Emails go to the mocked
 * {@code MessagingProviderApi}; outbox rows are dispatched by hand.
 */
class AccountSetupAndPasswordIntegrationTest extends NotificationManagementTestSupport {

	private static final String PLATFORM_HOST = "localhost";

	private static final Pattern LINK_TOKEN = Pattern.compile("token=([A-Za-z0-9_%-]+)");

	private static final String NEW_PASSWORD = "Brand-New-Password-42!";

	@Autowired
	private NotificationDispatchService dispatchService;

	private String platformToken;

	@BeforeEach
	void platformAdmin() {
		String email = "pa-" + UUID.randomUUID() + "@example.test";
		seedPlatformAdmin(email, RAW_PASSWORD);
		platformToken = platformAdminLogin(PLATFORM_HOST, email, RAW_PASSWORD).getBody().data().accessToken();
		clearInvocations(messagingProviderApi);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private MvcResult post(String path, String host, String token, Object body) {
		try {
			var request = MockMvcRequestBuilders.post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.HOST, host);
			if (token != null) {
				request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			}
			if (body != null) {
				request.content(objectMapper.writeValueAsString(body));
			}
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private MvcResult get(String path, String host, String token) {
		try {
			return mockMvc.perform(MockMvcRequestBuilders.get(path)
				.header(HttpHeaders.HOST, host)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private JsonNode json(MvcResult result) {
		String content = body(result);
		return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
	}

	private static String body(MvcResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (Exception ex) {
			return "";
		}
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	private Tenant approvedTenant(String prefix) {
		Tenant tenant = seedTenant(uniqueSubdomain(prefix), TenantStatus.PENDING_APPROVAL);
		MvcResult approved = post("/api/v1/platform-admin/tenants/" + tenant.getId() + "/approve", PLATFORM_HOST,
				platformToken, null);
		assertThat(status(approved)).as(body(approved)).isEqualTo(200);
		return tenant;
	}

	private List<UUID> pendingOutbox(UUID tenantId, String eventType) {
		return jdbcTemplate.queryForList("SELECT id FROM notification_outbox WHERE tenant_id = ? AND event_type = ? "
				+ "AND status = 'PENDING' ORDER BY created_at", UUID.class, tenantId, eventType);
	}

	/** Outbox rows for these emails are written asynchronously (review L-1): wait up to 5 s for them. */
	private List<UUID> awaitPendingOutbox(UUID tenantId, String eventType, int expected) {
		List<UUID> rows = pendingOutbox(tenantId, eventType);
		for (int i = 0; i < 50 && rows.size() < expected; i++) {
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			rows = pendingOutbox(tenantId, eventType);
		}
		return rows;
	}

	/** Dispatches every pending email of that type and returns the raw token from the last one sent. */
	private String sendAndCaptureToken(UUID tenantId, String eventType) {
		List<UUID> rows = awaitPendingOutbox(tenantId, eventType, 1);
		assertThat(rows).isNotEmpty();
		rows.forEach(dispatchService::dispatchOne);
		ArgumentCaptor<String> emailBody = ArgumentCaptor.forClass(String.class);
		verify(messagingProviderApi, atLeastOnce()).sendEmail(anyString(), anyString(), emailBody.capture());
		Matcher matcher = LINK_TOKEN.matcher(emailBody.getValue());
		assertThat(matcher.find()).as(emailBody.getValue()).isTrue();
		return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
	}

	private String tenantAdminEmail(UUID tenantId) {
		return jdbcTemplate.queryForObject(
				"SELECT email FROM tenant_user WHERE tenant_id = ? AND role = 'TENANT_ADMIN'", String.class, tenantId);
	}

	// ------------------------------------------------------------------
	// Invitation + account setup
	// ------------------------------------------------------------------

	@Test
	void approvalInvitesTheFirstAdminWhoSetsAPasswordThroughTheEmailedLink() {
		Tenant tenant = approvedTenant("inv");
		String host = hostFor(tenant.getSubdomain());
		String email = tenantAdminEmail(tenant.getId());
		assertThat(email).isEqualTo(tenant.getContactEmail());
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE email = ? AND tenant_id = ?",
				String.class, email, tenant.getId())).isEqualTo("invited");

		// The outbox row carries no token (MVP-018 / ADR-020).
		awaitPendingOutbox(tenant.getId(), "ACCOUNT_SETUP_INVITE", 1);
		String payload = jdbcTemplate.queryForObject("SELECT payload::text FROM notification_outbox WHERE tenant_id = ? "
				+ "AND event_type = 'ACCOUNT_SETUP_INVITE'", String.class, tenant.getId());
		assertThat(payload).doesNotContain("token");

		// An invited account can't log in - and looks exactly like a wrong password.
		MvcResult invitedLogin = post("/api/v1/auth/login", host, null, Map.of("email", email, "password", "anything"));
		assertThat(status(invitedLogin)).isEqualTo(401);
		assertThat(errorCode(invitedLogin)).isEqualTo("INVALID_CREDENTIALS");

		String token = sendAndCaptureToken(tenant.getId(), "ACCOUNT_SETUP_INVITE");
		// The raw token is stored nowhere: not in the token table, not in the in-app copy.
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM password_token WHERE token_hash = ?", Integer.class,
				token)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE body LIKE ?",
				Integer.class, "%" + token + "%")).isZero();

		MvcResult verified = post("/api/v1/auth/password-tokens/verify", host, null, Map.of("token", token));
		assertThat(status(verified)).isEqualTo(200);
		assertThat(json(verified).path("data").path("purpose").asString()).isEqualTo("ACCOUNT_SETUP");
		assertThat(json(verified).path("data").path("emailMasked").asString()).isNotEqualTo(email);

		MvcResult tooShort = post("/api/v1/auth/password-tokens/complete", host, null,
				Map.of("token", token, "newPassword", "short"));
		assertThat(status(tooShort)).isEqualTo(400);
		assertThat(errorCode(tooShort)).isEqualTo("VALIDATION_ERROR");

		MvcResult completed = post("/api/v1/auth/password-tokens/complete", host, null,
				Map.of("token", token, "newPassword", NEW_PASSWORD));
		assertThat(status(completed)).as(body(completed)).isEqualTo(204);
		assertThat(status(post("/api/v1/auth/login", host, null, Map.of("email", email, "password", NEW_PASSWORD))))
			.isEqualTo(200);

		// Single use.
		MvcResult again = post("/api/v1/auth/password-tokens/complete", host, null,
				Map.of("token", token, "newPassword", "Another-Password-99!"));
		assertThat(errorCode(again)).isEqualTo("TOKEN_INVALID");

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action IN "
				+ "('tenant_admin.invited', 'auth.account_setup_completed')", Integer.class, tenant.getId()))
			.isEqualTo(2);
	}

	@Test
	void aLinkFromOneTenantIsInvalidOnAnotherTenantsHost() {
		Tenant tenantA = approvedTenant("iso-a");
		Tenant tenantB = approvedTenant("iso-b");
		String tokenA = sendAndCaptureToken(tenantA.getId(), "ACCOUNT_SETUP_INVITE");
		String hostB = hostFor(tenantB.getSubdomain());

		assertThat(errorCode(post("/api/v1/auth/password-tokens/verify", hostB, null, Map.of("token", tokenA))))
			.isEqualTo("TOKEN_INVALID");
		assertThat(errorCode(post("/api/v1/auth/password-tokens/complete", hostB, null,
				Map.of("token", tokenA, "newPassword", NEW_PASSWORD))))
			.isEqualTo("TOKEN_INVALID");
		// Tenant A's link is untouched and still works on its own host.
		assertThat(status(post("/api/v1/auth/password-tokens/verify", hostFor(tenantA.getSubdomain()), null,
				Map.of("token", tokenA))))
			.isEqualTo(200);
	}

	@Test
	void platformAdminCanResendOrCorrectTheInvitationOnlyWhileItIsPending() {
		Tenant tenant = approvedTenant("resend");
		String host = hostFor(tenant.getSubdomain());
		String path = "/api/v1/platform-admin/tenants/" + tenant.getId() + "/admin-invitation";
		String oldToken = sendAndCaptureToken(tenant.getId(), "ACCOUNT_SETUP_INVITE");

		MvcResult corrected = post(path, PLATFORM_HOST, platformToken, Map.of("email", "fixed-owner@example.test"));
		assertThat(status(corrected)).as(body(corrected)).isEqualTo(202);
		assertThat(json(corrected).path("data").path("status").asString()).isEqualTo("INVITED");
		assertThat(tenantAdminEmail(tenant.getId())).isEqualTo("fixed-owner@example.test");
		// The link sent to the wrong address stopped working at once.
		assertThat(errorCode(post("/api/v1/auth/password-tokens/verify", host, null, Map.of("token", oldToken))))
			.isEqualTo("TOKEN_INVALID");

		// One send per minute.
		assertThat(status(post(path, PLATFORM_HOST, platformToken, null))).isEqualTo(429);

		String newToken = sendAndCaptureToken(tenant.getId(), "ACCOUNT_SETUP_INVITE");
		assertThat(status(post("/api/v1/auth/password-tokens/complete", host, null,
				Map.of("token", newToken, "newPassword", NEW_PASSWORD))))
			.isEqualTo(204);

		JsonNode firstAdmin = json(get("/api/v1/platform-admin/tenants/" + tenant.getId(), PLATFORM_HOST, platformToken))
			.path("data")
			.path("firstAdmin");
		assertThat(firstAdmin.path("status").asString()).isEqualTo("ACTIVE");
	}

	// ------------------------------------------------------------------
	// Forgot password
	// ------------------------------------------------------------------

	@Test
	void forgotPasswordAnswersTheSameForEveryEmailAndOnlyMailsActiveAccounts() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("forgot"));
		String host = hostFor(tenant.getSubdomain());
		seedTenantUser(tenant.getId(), "active@example.test", RAW_PASSWORD, Role.STUDENT);
		TenantUser suspended = seedTenantUser(tenant.getId(), "suspended@example.test", RAW_PASSWORD, Role.STUDENT);
		suspendTenantUser(suspended.getId());
		String oldAccess = loginAndGetToken(host, "active@example.test");

		Map<String, Integer> statuses = new HashMap<>();
		for (String email : List.of("active@example.test", "nobody@example.test", "suspended@example.test")) {
			MvcResult response = post("/api/v1/auth/password-reset/request", host, null, Map.of("email", email));
			statuses.put(email, status(response));
			assertThat(json(response).path("data").path("message").asString()).startsWith("If an account exists");
		}
		assertThat(statuses.values()).containsOnly(202);
		assertThat(awaitPendingOutbox(tenant.getId(), "PASSWORD_RESET", 1)).hasSize(1);

		String token = sendAndCaptureToken(tenant.getId(), "PASSWORD_RESET");
		assertThat(json(post("/api/v1/auth/password-tokens/verify", host, null, Map.of("token", token))).path("data")
			.path("purpose")
			.asString()).isEqualTo("PASSWORD_RESET");
		assertThat(status(post("/api/v1/auth/password-tokens/complete", host, null,
				Map.of("token", token, "newPassword", NEW_PASSWORD))))
			.isEqualTo(204);

		// Every earlier session is revoked; the new password works, the old one doesn't.
		assertThat(status(get("/api/v1/notifications", host, oldAccess))).isEqualTo(401);
		assertThat(status(post("/api/v1/auth/login", host, null,
				Map.of("email", "active@example.test", "password", RAW_PASSWORD))))
			.isEqualTo(401);
		assertThat(status(post("/api/v1/auth/login", host, null,
				Map.of("email", "active@example.test", "password", NEW_PASSWORD))))
			.isEqualTo(200);
	}

	@Test
	void forgotPasswordOnAnotherTenantsHostNeverTouchesThisTenantsAccount() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("fp-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("fp-b"));
		seedTenantUser(tenantA.getId(), "only-in-a@example.test", RAW_PASSWORD, Role.STUDENT);

		post("/api/v1/auth/password-reset/request", hostFor(tenantB.getSubdomain()), null,
				Map.of("email", "only-in-a@example.test"));

		assertThat(pendingOutbox(tenantA.getId(), "PASSWORD_RESET")).isEmpty();
		assertThat(pendingOutbox(tenantB.getId(), "PASSWORD_RESET")).isEmpty();
		verify(messagingProviderApi, never()).sendEmail(anyString(), anyString(), anyString());
	}

	// ------------------------------------------------------------------
	// Change password + enforcement
	// ------------------------------------------------------------------

	@Test
	void anAccountThatMustChangeItsPasswordCanDoNothingElseUntilItDoes() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("must"));
		String host = hostFor(tenant.getSubdomain());
		TenantUser user = seedTenantUser(tenant.getId(), "temp@example.test", RAW_PASSWORD, Role.STUDENT);
		jdbcTemplate.update("UPDATE tenant_user SET must_change_password = true WHERE id = ?", user.getId());
		// Plain login (not loginAndGetToken, which marks the account onboarded).
		String access = login(host, "temp@example.test", RAW_PASSWORD).getBody().data().accessToken();
		String otherSession = login(host, "temp@example.test", RAW_PASSWORD).getBody().data().accessToken();

		MvcResult blocked = get("/api/v1/notifications", host, access);
		assertThat(status(blocked)).isEqualTo(403);
		assertThat(errorCode(blocked)).isEqualTo("PASSWORD_CHANGE_REQUIRED");

		MvcResult wrongCurrent = post("/api/v1/auth/password", host, access,
				Map.of("currentPassword", WRONG_PASSWORD, "newPassword", NEW_PASSWORD));
		assertThat(status(wrongCurrent)).isEqualTo(400);
		assertThat(json(wrongCurrent).path("error").path("fieldErrors").path(0).path("field").asString())
			.isEqualTo("currentPassword");

		MvcResult sameAsCurrent = post("/api/v1/auth/password", host, access,
				Map.of("currentPassword", RAW_PASSWORD, "newPassword", RAW_PASSWORD));
		assertThat(status(sameAsCurrent)).isEqualTo(400);

		MvcResult changed = post("/api/v1/auth/password", host, access,
				Map.of("currentPassword", RAW_PASSWORD, "newPassword", NEW_PASSWORD));
		assertThat(status(changed)).as(body(changed)).isEqualTo(200);
		String fresh = json(changed).path("data").path("accessToken").asString();

		assertThat(status(get("/api/v1/notifications", host, fresh))).isEqualTo(200);
		// The other session was revoked; the current one continues.
		assertThat(status(get("/api/v1/notifications", host, otherSession))).isEqualTo(401);
		assertThat(jdbcTemplate.queryForObject("SELECT must_change_password FROM tenant_user WHERE id = ?",
				Boolean.class, user.getId())).isFalse();
	}

}
