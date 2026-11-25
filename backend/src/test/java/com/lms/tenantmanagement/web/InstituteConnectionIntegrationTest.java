package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * INST-QR-01 end to end through the real filter chain: both admin placements, QR
 * decoding, identity stability across renames and address changes, the public
 * resolver's fail-safe behaviour, cross-tenant and role negatives, and the V127
 * database invariants (immutable, never reassigned).
 */
class InstituteConnectionIntegrationTest extends AuthIntegrationTestSupport {

	private static final String TENANT_PATH = "/api/v1/tenant-config/institute-connection";

	private static final String PUBLIC_PATH = "/api/v1/public/institute-connections/";

	private static final String BASE = "https://connect.lms.test/connect/v1/";

	// ------------------------------------------------------------------ admin placements

	@Test
	void tenantAdminAndPlatformAdminGetTheSameCanonicalUrlAndAScannableQr() throws Exception {
		Fixture a = tenantWithAdmin("iq-same-a", "admin@example.test");
		Fixture b = tenantWithAdmin("iq-same-b", "admin.b@example.test");
		String platform = platformAdminToken();

		JsonNode tenantView = data(call(a.host, a.token, get(TENANT_PATH)));
		JsonNode platformView = data(call(null, platform, get(platformPath(a.tenant.getId()))));
		JsonNode platformViewB = data(call(null, platform, get(platformPath(b.tenant.getId()))));

		String code = dbCode(a.tenant.getId());
		assertThat(code).matches("^[a-z0-9]{12}$");
		assertThat(tenantView.get("connectionUrl").asString()).isEqualTo(BASE + code);
		assertThat(platformView.get("connectionUrl").asString()).isEqualTo(tenantView.get("connectionUrl").asString());
		assertThat(platformView.get("tenantId").asString()).isEqualTo(a.tenant.getId().toString());
		assertThat(platformViewB.get("tenantId").asString()).isEqualTo(b.tenant.getId().toString());
		assertThat(platformViewB.get("connectionUrl").asString()).isEqualTo(BASE + dbCode(b.tenant.getId()))
			.isNotEqualTo(tenantView.get("connectionUrl").asString());

		// Independent decode of the delivered PNG gives exactly the canonical URL.
		assertThat(decode(tenantView.get("qrPngBase64").asString())).isEqualTo(BASE + code);
		assertThat(decode(platformViewB.get("qrPngBase64").asString())).isEqualTo(BASE + dbCode(b.tenant.getId()));
		assertThat(tenantView.get("connectable").asBoolean()).isTrue();
		assertThat(tenantView.get("instituteName").asString()).isEqualTo(a.tenant.getName());
	}

	@Test
	void onlyInstituteConfigViewersAndPlatformAdminsMaySeeIt() {
		Fixture a = tenantWithAdmin("iq-roles", "admin@example.test");
		seedTenantUser(a.tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(a.tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		seedActiveStudent(a.tenant.getId(), "student@example.test");

		assertThat(status(call(a.host, token(a.host, "teacher@example.test"), get(TENANT_PATH)))).isEqualTo(403);
		assertThat(status(call(a.host, token(a.host, "auditor@example.test"), get(TENANT_PATH)))).isEqualTo(200);
		assertThat(status(call(a.host, null, get(TENANT_PATH)))).isEqualTo(401);
		// A tenant admin token is not a platform admin token.
		assertThat(status(call(a.host, a.token, get(platformPath(a.tenant.getId()))))).isIn(401, 403);
		assertThat(status(call(null, platformAdminToken(), get(platformPath(UUID.randomUUID()))))).isEqualTo(404);
	}

	@Test
	void aTenantAdminCannotReachAnotherInstitutesQr() {
		Fixture a = tenantWithAdmin("iq-cross-a", "admin@example.test");
		Fixture b = tenantWithAdmin("iq-cross-b", "admin.b@example.test");

		// A's token on B's host is refused outright, never answered with B's (or A's) QR.
		assertThat(status(call(b.host, a.token, get(TENANT_PATH)))).isIn(401, 403);
		// There is no tenant parameter to tamper with: the extra id is ignored.
		JsonNode own = data(call(a.host, a.token, get(TENANT_PATH + "?tenantId=" + b.tenant.getId())));
		assertThat(own.get("tenantId").asString()).isEqualTo(a.tenant.getId().toString());
		assertThat(own.get("connectionUrl").asString()).isEqualTo(BASE + dbCode(a.tenant.getId()));
	}

	// ------------------------------------------------------------------ stability

	@Test
	void renamesSubdomainAndCustomDomainChangesKeepTheSameQrIdentity() {
		Fixture a = tenantWithAdmin("iq-stable", "admin@example.test");
		String platform = platformAdminToken();
		String before = data(call(null, platform, get(platformPath(a.tenant.getId())))).get("connectionUrl").asString();
		String code = dbCode(a.tenant.getId());

		JsonNode resolved = data(call(null, null, get(PUBLIC_PATH + code)));
		assertThat(resolved.get("signInUrl").asString()).isEqualTo("http://" + a.host + "/login");

		String newSub = uniqueSubdomain("iq-renamed");
		jdbcTemplate.update("UPDATE tenant SET name = 'Renamed Academy', subdomain = ?, custom_domains_enabled = true "
				+ "WHERE id = ?", newSub, a.tenant.getId());
		String customHost = "learn-" + UUID.randomUUID().toString().substring(0, 8) + ".example.org";
		jdbcTemplate.update("INSERT INTO tenant_custom_domain (id, tenant_id, hostname, status, verification_code, "
				+ "is_primary, verified_at, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', 'v', true, now(), now(), now())",
				UUID.randomUUID(), a.tenant.getId(), customHost);

		JsonNode after = data(call(null, platform, get(platformPath(a.tenant.getId()))));
		assertThat(after.get("connectionUrl").asString()).isEqualTo(before);
		assertThat(after.get("instituteName").asString()).isEqualTo("Renamed Academy");
		assertThat(decode(after.get("qrPngBase64").asString())).isEqualTo(before);

		// The same code now resolves to the verified primary custom domain's sign-in page.
		JsonNode moved = data(call(null, null, get(PUBLIC_PATH + code)));
		assertThat(moved.get("signInUrl").asString()).isEqualTo("http://" + customHost + "/login");
		assertThat(moved.get("instituteName").asString()).isEqualTo("Renamed Academy");
	}

	// ------------------------------------------------------------------ public resolver

	@Test
	void resolverReturnsOnlyAllowlistedPublicFieldsAndNoSession() throws Exception {
		Fixture a = tenantWithAdmin("iq-public", "admin@example.test");
		String code = dbCode(a.tenant.getId());

		MvcResult result = call(null, null, get(PUBLIC_PATH + code));
		assertThat(status(result)).isEqualTo(200);
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
		JsonNode data = data(result);
		assertThat(data.propertyNames()).containsExactlyInAnyOrder("version", "connectionCode", "connectionUrl",
				"instituteName", "logoUrl", "siteOrigin", "signInUrl", "mobileApps");
		assertThat(data.get("version").asInt()).isEqualTo(1);
		assertThat(data.get("connectionUrl").asString()).isEqualTo(BASE + code);
		assertThat(data.get("siteOrigin").asString()).isEqualTo("http://" + a.host);
		assertThat(result.getResponse().getContentAsString()).doesNotContain(a.tenant.getId().toString())
			.doesNotContain("contact-");
		assertThat(data.get("mobileApps").get("androidUrl").isNull()).isTrue();
		assertThat(data.get("mobileApps").get("iosUrl").isNull()).isTrue();
	}

	@Test
	void resolverIgnoresHostAndRedirectInputs() {
		Fixture a = tenantWithAdmin("iq-host-a", "admin@example.test");
		Fixture b = tenantWithAdmin("iq-host-b", "admin.b@example.test");
		String code = dbCode(a.tenant.getId());

		// Sent on B's host (or a hostile one) with redirect-looking params: still A, still A's own sign-in.
		for (String host : new String[] { b.host, "evil.example", null }) {
			JsonNode data = data(call(host, null,
					get(PUBLIC_PATH + code + "?redirect=https://evil.example&next=//evil.example&signInUrl=x")));
			assertThat(data.get("instituteName").asString()).as(String.valueOf(host)).isEqualTo(a.tenant.getName());
			assertThat(data.get("signInUrl").asString()).isEqualTo("http://" + a.host + "/login");
		}
	}

	@Test
	void unknownMalformedAndUnavailableInstitutesFailTheSameSafeWay() throws Exception {
		Fixture a = tenantWithAdmin("iq-unknown", "admin@example.test");
		Tenant suspended = seedTenant(uniqueSubdomain("iq-suspended"), TenantStatus.SUSPENDED);
		Tenant pending = seedTenant(uniqueSubdomain("iq-pending"), TenantStatus.PENDING_APPROVAL);

		String[] bad = { "000000000000", "ABCDEFGHIJKL", dbCode(a.tenant.getId()).toUpperCase(), "short",
				dbCode(a.tenant.getId()) + "0", "x'or'1'='1", dbCode(suspended.getId()), dbCode(pending.getId()) };
		for (String code : bad) {
			MvcResult result = call(a.host, null, get(PUBLIC_PATH + code));
			assertThat(status(result)).as(code).isEqualTo(404);
			assertThat(errorCode(result)).as(code).isEqualTo("NOT_FOUND");
			assertThat(result.getResponse().getContentAsString()).as(code)
				.doesNotContain(a.tenant.getName())
				.doesNotContain(suspended.getName());
		}

		// Encoded traversal may be refused by the firewall (400) instead - either way, nothing leaks.
		for (String code : new String[] { "%2e%2e%2fadmin", "..%2f..%2fplatform-admin" }) {
			MvcResult result = call(a.host, null, get(PUBLIC_PATH + code));
			assertThat(status(result)).as(code).isIn(400, 404);
			assertThat(result.getResponse().getContentAsString()).doesNotContain(a.tenant.getName());
		}

		// Platform Admin still sees a non-connectable institute's QR, flagged as such.
		JsonNode view = data(call(null, platformAdminToken(), get(platformPath(suspended.getId()))));
		assertThat(view.get("connectable").asBoolean()).isFalse();
	}

	@Test
	void scanningGrantsNothing() {
		Fixture a = tenantWithAdmin("iq-nothing", "admin@example.test");
		String code = dbCode(a.tenant.getId());
		call(null, null, get(PUBLIC_PATH + code));

		// The code is not a credential anywhere.
		MvcResult asBearer = call(a.host, code, get(TENANT_PATH));
		assertThat(status(asBearer)).isEqualTo(401);
	}

	// ------------------------------------------------------------------ database invariants

	@Test
	void codesAreImmutableAndNeverReassigned() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("iq-db"));
		String code = dbCode(tenant.getId());

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE tenant SET connection_code = 'aaaaaaaaaaaa' WHERE id = ?",
				tenant.getId())).hasMessageContaining("immutable");

		jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", tenant.getId());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT former_tenant_id FROM retired_institute_connection_code WHERE connection_code = ?", UUID.class,
				code)).isEqualTo(tenant.getId());

		String sub = uniqueSubdomain("iq-reuse");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO tenant (id, name, subdomain, status, requested_plan, contact_name, contact_email, "
						+ "created_at, updated_at, connection_code) VALUES (?, 'Reuse', ?, 'active', 'starter', 'c', "
						+ "'c@example.test', now(), now(), ?)",
				UUID.randomUUID(), sub, code)).hasMessageContaining("retired");
		assertThat(status(call(null, null, get(PUBLIC_PATH + code)))).isEqualTo(404);
	}

	// ------------------------------------------------------------------ helpers

	private record Fixture(Tenant tenant, String host, String token) {
	}

	private Fixture tenantWithAdmin(String prefix, String email) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		return new Fixture(tenant, host, token(host, email));
	}

	private String token(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).as("login " + email).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private String platformAdminToken() {
		String email = "platform-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		seedPlatformAdmin(email, RAW_PASSWORD);
		return platformAdminLogin(null, email, RAW_PASSWORD).getBody().data().accessToken();
	}

	private static String platformPath(UUID tenantId) {
		return "/api/v1/platform-admin/tenants/" + tenantId + "/institute-connection";
	}

	private String dbCode(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT connection_code FROM tenant WHERE id = ?", String.class, tenantId);
	}

	private static String decode(String base64Png) throws RuntimeException {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64Png)));
			assertThat(image.getWidth()).as("scan resolution").isGreaterThanOrEqualTo(300);
			assertThat(image.getRGB(0, 0)).as("white quiet zone").isEqualTo(0xFFFFFFFF);
			return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
				.getText();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder request) {
		if (host != null) {
			request.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private JsonNode data(MvcResult result) {
		assertThat(status(result)).as(result.getRequest().getRequestURI()).isEqualTo(200);
		return body(result).get("data");
	}

	private String errorCode(MvcResult result) {
		return body(result).get("error").get("code").asString();
	}

	private JsonNode body(MvcResult result) {
		try {
			return objectMapper.readTree(result.getResponse().getContentAsString());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
