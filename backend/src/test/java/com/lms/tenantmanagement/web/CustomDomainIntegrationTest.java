package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.service.CustomDomainService;
import com.lms.tenantmanagement.service.DnsRecordLookup;
import com.lms.tenantmanagement.service.DnsRecordLookup.DnsLookupException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * TEN-DOM-01 / ADR-021 end to end through the real filter chain: add, verify
 * (DNS stubbed via {@link DnsRecordLookup}), resolution by custom host,
 * primary-domain links, removal, Platform Admin switch and suspension, the
 * daily re-check, and the mandatory cross-tenant cases.
 */
class CustomDomainIntegrationTest extends AuthIntegrationTestSupport {

	private static final String PATH = "/api/v1/tenant-config/custom-domains";

	private static final String PROBE_PATH = "/api/v1/public/tenant-config/branding";

	private static final String ADMIN = "admin@example.test";

	@MockitoBean
	private DnsRecordLookup dns;

	@Autowired
	private CustomDomainService customDomainService;

	@Autowired
	private TenantLookupApi tenantLookupApi;

	@AfterEach
	void resetDns() {
		reset(dns);
	}

	// ------------------------------------------------------------------ gating

	@Test
	void switchedOffByDefault() {
		Fixture fx = tenantWithAdmin("cd-off", false);

		JsonNode overview = data(call(fx.host, fx.token, get(PATH)));
		assertThat(overview.get("enabled").asBoolean()).isFalse();
		assertThat(overview.get("available").asBoolean()).isTrue();

		MvcResult add = call(fx.host, fx.token, jsonPost(PATH, Map.of("hostname", uniqueHost())));
		assertThat(add.getResponse().getStatus()).isEqualTo(403);
		assertThat(errorCode(add)).isEqualTo("CUSTOM_DOMAINS_DISABLED");
	}

	@Test
	void onlyTenantAdminChangesAndAuditorOnlyViews() {
		Fixture fx = tenantWithAdmin("cd-roles", true);
		seedTenantUser(fx.tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(fx.tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String teacher = token(fx.host, "teacher@example.test");
		String auditor = token(fx.host, "auditor@example.test");

		assertThat(call(fx.host, teacher, get(PATH)).getResponse().getStatus()).isEqualTo(403);
		assertThat(call(fx.host, auditor, get(PATH)).getResponse().getStatus()).isEqualTo(200);
		assertThat(call(fx.host, auditor, jsonPost(PATH, Map.of("hostname", uniqueHost()))).getResponse()
			.getStatus()).isEqualTo(403);
	}

	@Test
	void invalidHostnamesAreRejected() {
		Fixture fx = tenantWithAdmin("cd-invalid", true);

		for (String bad : List.of("https://learn.abc.lk", "localhost", "intranet", "demo.lms.test", "127.0.0.1")) {
			MvcResult result = call(fx.host, fx.token, jsonPost(PATH, Map.of("hostname", bad)));
			assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
		}
	}

	@Test
	void limitPerTenantIsEnforced() {
		Fixture fx = tenantWithAdmin("cd-limit", true);
		for (int i = 0; i < 3; i++) {
			add(fx, uniqueHost());
		}

		MvcResult fourth = call(fx.host, fx.token, jsonPost(PATH, Map.of("hostname", uniqueHost())));
		assertThat(fourth.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(fourth)).isEqualTo("CUSTOM_DOMAIN_LIMIT_REACHED");
	}

	// ------------------------------------------------------------------ verify + resolve

	@Test
	void pendingDomainDoesNotResolveAndVerifiedDomainDoes() {
		Fixture fx = tenantWithAdmin("cd-flow", true);
		String host = uniqueHost();
		JsonNode added = add(fx, host);
		assertThat(added.get("status").asString()).isEqualTo("PENDING_VERIFICATION");
		assertThat(added.get("dnsRecords").get(0).get("value").asString()).isEqualTo("tenants.lms.test");
		assertThat(added.get("dnsRecords").get(1).get("name").asString()).isEqualTo("_lms-verify." + host);

		assertThat(probe(host)).as("pending domain must not resolve").isEqualTo(403);

		stubRecords(host, code(added));
		JsonNode verified = verify(fx, id(added));
		assertThat(verified.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(verified.get("verifiedAt").isNull()).isFalse();

		assertThat(probe(host)).isEqualTo(200);
		HttpResult<LoginResponse> login = login(host, ADMIN, RAW_PASSWORD);
		assertThat(login.getStatusCode()).as("sign-in on the custom domain").isEqualTo(HttpStatus.OK);
		assertThat(auditActions(id(added))).contains("custom_domain.added", "custom_domain.verified");
	}

	@Test
	void failedVerificationExplainsWhatIsMissing() {
		Fixture fx = tenantWithAdmin("cd-fail", true);
		String host = uniqueHost();
		JsonNode added = add(fx, host);

		JsonNode noRecords = verify(fx, id(added));
		assertThat(noRecords.get("status").asString()).isEqualTo("PENDING_VERIFICATION");
		assertThat(noRecords.get("lastError").asString()).contains("No CNAME record");

		when(dns.lookup(host, "CNAME")).thenReturn(List.of("tenants.lms.test."));
		when(dns.lookup("_lms-verify." + host, "TXT")).thenReturn(List.of("wrong-code"));
		assertThat(verify(fx, id(added)).get("lastError").asString()).contains("verification code");

		when(dns.lookup(anyString(), anyString())).thenThrow(new DnsLookupException("timeout", null));
		JsonNode inconclusive = verify(fx, id(added));
		assertThat(inconclusive.get("status").asString()).isEqualTo("PENDING_VERIFICATION");
		assertThat(inconclusive.get("lastError").asString()).contains("didn't complete");
		assertThat(probe(host)).isEqualTo(403);
	}

	@Test
	void unknownHostsAndLookalikeHostsNeverResolve() {
		Fixture fx = tenantWithAdmin("cd-lookalike", true);

		assertThat(probe(uniqueHost())).as("unknown custom host").isEqualTo(403);
		// The first label equals a real tenant's subdomain, but the host is not
		// on the platform domain: must never resolve to that tenant.
		assertThat(probe(fx.tenant.getSubdomain() + ".evil.example")).isEqualTo(403);
		// Two labels under the platform domain is not a tenant subdomain.
		assertThat(probe("x." + fx.host)).isEqualTo(403);
		assertThat(probe(fx.host)).as("the subdomain keeps working").isEqualTo(200);
	}

	// ------------------------------------------------------------------ primary + links

	@Test
	void primaryDomainIsUsedForTenantLinks() {
		Fixture fx = tenantWithAdmin("cd-primary", true);
		String template = "http://{subdomain}.lms.test:3000";
		assertThat(tenantLookupApi.tenantOrigin(fx.tenant.getId(), template))
			.contains("http://" + fx.host + ":3000");

		String pendingHost = uniqueHost();
		JsonNode pending = add(fx, pendingHost);
		MvcResult pendingPrimary = call(fx.host, fx.token, post(PATH + "/{id}/primary", UUID.fromString(id(pending))));
		assertThat(pendingPrimary.getResponse().getStatus()).as("unverified cannot be primary").isEqualTo(409);

		String first = activeDomain(fx);
		String second = activeDomain(fx);
		makePrimary(fx, domainIdOf(fx, first));
		makePrimary(fx, domainIdOf(fx, second));

		assertThat(tenantLookupApi.tenantOrigin(fx.tenant.getId(), template)).contains("http://" + second + ":3000");
		JsonNode domains = data(call(fx.host, fx.token, get(PATH))).get("domains");
		long primaries = 0;
		for (JsonNode domain : domains) {
			primaries += domain.get("primary").asBoolean() ? 1 : 0;
		}
		assertThat(primaries).as("one primary at a time").isEqualTo(1);
	}

	// ------------------------------------------------------------------ remove

	@Test
	void removedDomainStopsResolvingAndIsFreeAgain() {
		Fixture a = tenantWithAdmin("cd-remove-a", true);
		Fixture b = tenantWithAdmin("cd-remove-b", true);
		String host = activeDomain(a);
		assertThat(probe(host)).isEqualTo(200);

		MvcResult removed = call(a.host, a.token, delete(PATH + "/{id}", UUID.fromString(domainIdOf(a, host))));
		assertThat(removed.getResponse().getStatus()).isEqualTo(200);

		assertThat(probe(host)).as("cache evicted on removal").isEqualTo(403);
		assertThat(data(call(a.host, a.token, get(PATH))).get("domains").size()).isZero();
		assertThat(call(b.host, b.token, jsonPost(PATH, Map.of("hostname", host))).getResponse().getStatus())
			.isEqualTo(201);
	}

	// ------------------------------------------------------------------ cross-tenant

	@Test
	@Tag("cross-tenant")
	void aHostnameBelongsToOneTenant() {
		Fixture a = tenantWithAdmin("cd-taken-a", true);
		Fixture b = tenantWithAdmin("cd-taken-b", true);
		String host = uniqueHost();
		add(a, host);

		MvcResult taken = call(b.host, b.token, jsonPost(PATH, Map.of("hostname", host.toUpperCase())));
		assertThat(taken.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(taken)).isEqualTo("CUSTOM_DOMAIN_TAKEN");
	}

	@Test
	@Tag("cross-tenant")
	void anotherTenantsDomainIsNotFoundForEveryAction() {
		Fixture a = tenantWithAdmin("cd-cross-a", true);
		Fixture b = tenantWithAdmin("cd-cross-b", true);
		String host = activeDomain(a);
		UUID aDomain = UUID.fromString(domainIdOf(a, host));

		assertThat(call(b.host, b.token, post(PATH + "/{id}/verify", aDomain)).getResponse().getStatus())
			.isEqualTo(404);
		assertThat(call(b.host, b.token, post(PATH + "/{id}/primary", aDomain)).getResponse().getStatus())
			.isEqualTo(404);
		assertThat(call(b.host, b.token, delete(PATH + "/{id}", aDomain)).getResponse().getStatus()).isEqualTo(404);
		assertThat(data(call(b.host, b.token, get(PATH))).get("domains").size()).isZero();
		assertThat(probe(host)).as("A's domain untouched").isEqualTo(200);
	}

	@Test
	@Tag("cross-tenant")
	void aCustomDomainServesOnlyItsOwnTenant() {
		Fixture a = tenantWithAdmin("cd-host-a", true);
		Fixture b = tenantWithAdmin("cd-host-b", true);
		String host = activeDomain(a);

		// B's token on A's custom domain: the request resolves to A, so B's token is refused.
		int status = call(host, b.token, get(PATH)).getResponse().getStatus();
		assertThat(status).isIn(401, 403);
		// B's credentials don't sign in on A's domain.
		assertThat(login(host, ADMIN + ".b", RAW_PASSWORD).getStatusCode()).isNotEqualTo(HttpStatus.OK);
		assertThat(data(call(host, a.token, get(PATH))).get("domains").size()).isEqualTo(1);
	}

	// ------------------------------------------------------------------ Platform Admin

	@Test
	void platformAdminSwitchesTheFeatureAndSuspendsDomains() {
		Fixture fx = tenantWithAdmin("cd-platform", false);
		String admin = platformAdminToken();
		String base = "/api/v1/platform-admin/tenants/" + fx.tenant.getId();

		JsonNode enabled = data(call(null, admin, jsonPut(base + "/custom-domains-enabled", Map.of("enabled", true))));
		assertThat(enabled.get("enabled").asBoolean()).isTrue();
		String host = activeDomain(fx);
		UUID domainId = UUID.fromString(domainIdOf(fx, host));
		assertThat(data(call(null, admin, get(base + "/custom-domains"))).get("domains").size()).isEqualTo(1);

		MvcResult noReason = call(null, admin, jsonPost(base + "/custom-domains/" + domainId + "/suspend", Map.of()));
		assertThat(noReason.getResponse().getStatus()).isEqualTo(400);

		JsonNode suspended = data(call(null, admin,
				jsonPost(base + "/custom-domains/" + domainId + "/suspend", Map.of("reason", "Phishing report"))));
		assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
		assertThat(probe(host)).as("suspended domain stops resolving").isEqualTo(403);
		assertThat(call(fx.host, fx.token, post(PATH + "/{id}/verify", domainId)).getResponse().getStatus())
			.isEqualTo(409);
		assertThat(call(fx.host, fx.token, delete(PATH + "/{id}", domainId)).getResponse().getStatus())
			.isEqualTo(409);
		assertThat(auditActions(domainId)).contains("custom_domain.suspended");
		assertThat(auditActions(fx.tenant.getId())).contains("tenant.custom_domains_enabled");
	}

	@Test
	void switchingTheFeatureOffStopsResolution() {
		Fixture fx = tenantWithAdmin("cd-switch-off", true);
		String host = activeDomain(fx);
		String admin = platformAdminToken();
		assertThat(probe(host)).isEqualTo(200);

		call(null, admin, jsonPut("/api/v1/platform-admin/tenants/" + fx.tenant.getId() + "/custom-domains-enabled",
				Map.of("enabled", false)));

		assertThat(probe(host)).isEqualTo(403);
		assertThat(probe(fx.host)).isEqualTo(200);
	}

	@Test
	void tenantUsersCannotUsePlatformAdminEndpoints() {
		Fixture fx = tenantWithAdmin("cd-not-platform", false);

		MvcResult result = call(null, fx.token, jsonPut(
				"/api/v1/platform-admin/tenants/" + fx.tenant.getId() + "/custom-domains-enabled", Map.of("enabled", true)));
		assertThat(result.getResponse().getStatus()).isIn(401, 403);
	}

	// ------------------------------------------------------------------ daily re-check

	@Test
	void recheckBreaksAndRestoresADomain() {
		Fixture fx = tenantWithAdmin("cd-recheck", true);
		String host = activeDomain(fx);
		String code = domainOf(fx, host).get("dnsRecords").get(1).get("value").asString();

		reset(dns);
		customDomainService.recheckAll();
		assertThat(domainOf(fx, host).get("status").asString()).isEqualTo("DNS_BROKEN");
		assertThat(probe(host)).isEqualTo(403);

		when(dns.lookup(anyString(), anyString())).thenThrow(new DnsLookupException("timeout", null));
		customDomainService.recheckAll();
		assertThat(domainOf(fx, host).get("status").asString()).as("inconclusive changes nothing")
			.isEqualTo("DNS_BROKEN");

		reset(dns);
		stubRecords(host, code);
		customDomainService.recheckAll();
		assertThat(domainOf(fx, host).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(probe(host)).isEqualTo(200);
	}

	// ------------------------------------------------------------------ helpers

	private record Fixture(Tenant tenant, String host, String token) {

	}

	private Fixture tenantWithAdmin(String prefix, boolean customDomainsEnabled) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		if (customDomainsEnabled) {
			jdbcTemplate.update("UPDATE tenant SET custom_domains_enabled = true WHERE id = ?", tenant.getId());
		}
		String email = prefix.endsWith("-b") ? ADMIN + ".b" : ADMIN;
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

	private static String uniqueHost() {
		return "learn-" + UUID.randomUUID().toString().substring(0, 8) + ".example.org";
	}

	private JsonNode add(Fixture fx, String host) {
		MvcResult result = call(fx.host, fx.token, jsonPost(PATH, Map.of("hostname", host)));
		assertThat(result.getResponse().getStatus()).as("add " + host).isEqualTo(201);
		return data(result);
	}

	private JsonNode verify(Fixture fx, String id) {
		MvcResult result = call(fx.host, fx.token, post(PATH + "/{id}/verify", UUID.fromString(id)));
		assertThat(result.getResponse().getStatus()).as("verify").isEqualTo(200);
		return data(result);
	}

	private void makePrimary(Fixture fx, String id) {
		MvcResult result = call(fx.host, fx.token, post(PATH + "/{id}/primary", UUID.fromString(id)));
		assertThat(result.getResponse().getStatus()).as("primary").isEqualTo(200);
	}

	/** Adds and verifies a fresh domain; returns its hostname. */
	private String activeDomain(Fixture fx) {
		String host = uniqueHost();
		JsonNode added = add(fx, host);
		stubRecords(host, code(added));
		assertThat(verify(fx, id(added)).get("status").asString()).isEqualTo("ACTIVE");
		return host;
	}

	private void stubRecords(String host, String code) {
		when(dns.lookup(host, "CNAME")).thenReturn(List.of("tenants.lms.test."));
		when(dns.lookup("_lms-verify." + host, "TXT")).thenReturn(List.of(code));
	}

	private JsonNode domainOf(Fixture fx, String host) {
		for (JsonNode domain : data(call(fx.host, fx.token, get(PATH))).get("domains")) {
			if (domain.get("hostname").asString().equals(host)) {
				return domain;
			}
		}
		throw new AssertionError("no domain " + host);
	}

	private String domainIdOf(Fixture fx, String host) {
		return domainOf(fx, host).get("id").asString();
	}

	private static String id(JsonNode domain) {
		return domain.get("id").asString();
	}

	private static String code(JsonNode domain) {
		return domain.get("dnsRecords").get(1).get("value").asString();
	}

	private int probe(String host) {
		return call(host, null, get(PROBE_PATH)).getResponse().getStatus();
	}

	private List<String> auditActions(UUID targetId) {
		return jdbcTemplate.queryForList("SELECT action FROM audit_log WHERE target_id = ?", String.class, targetId);
	}

	private List<String> auditActions(String targetId) {
		return auditActions(UUID.fromString(targetId));
	}

	private MockHttpServletRequestBuilder jsonPost(String path, Object body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private MockHttpServletRequestBuilder jsonPut(String path, Object body) {
		return put(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
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

	private JsonNode data(MvcResult result) {
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
