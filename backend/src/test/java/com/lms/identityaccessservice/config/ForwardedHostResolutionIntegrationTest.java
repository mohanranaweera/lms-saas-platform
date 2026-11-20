package com.lms.identityaccessservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * TEN-DOM-01: the whole forwarded-host path through a real Tomcat, as in the
 * local and prod profiles ({@code server.forward-headers-strategy: native}).
 * The browser calls {@code /api} on the site's own address; the Next dev
 * server (or Nginx) proxies it here with its own Host and the site's address
 * in {@code X-Forwarded-Host}. Tomcat's RemoteIpValve trusts that header from
 * loopback, so the test client (127.0.0.1) plays the proxy.
 */
@TestPropertySource(properties = "server.forward-headers-strategy=native")
class ForwardedHostResolutionIntegrationTest extends AuthIntegrationTestSupport {

	private static final String PROBE = "/api/v1/public/tenant-config/branding";

	@Test
	void theProxyHostHeaderIsNotATenant() {
		seedActiveTenant(uniqueSubdomain("fwd-none"));

		assertThat(probe(null).getStatusCode().value()).isEqualTo(403);
	}

	@Test
	void forwardedPlatformSubdomainResolvesTheTenant() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("fwd-sub"));

		assertThat(probe(hostFor(tenant.getSubdomain()) + ":3000").getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void forwardedCustomDomainResolvesOnlyWhenActive() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("fwd-custom"));
		jdbcTemplate.update("UPDATE tenant SET custom_domains_enabled = true WHERE id = ?", tenant.getId());
		String active = "learn-" + UUID.randomUUID().toString().substring(0, 8) + ".example.org";
		String pending = "pending-" + UUID.randomUUID().toString().substring(0, 8) + ".example.org";
		insertDomain(tenant.getId(), active, "ACTIVE");
		insertDomain(tenant.getId(), pending, "PENDING_VERIFICATION");

		assertThat(probe(active + ":3000").getStatusCode().value()).isEqualTo(200);
		assertThat(probe(pending + ":3000").getStatusCode().value()).isEqualTo(403);
	}

	private ResponseEntity<String> probe(String forwardedHost) {
		HttpHeaders headers = new HttpHeaders();
		if (forwardedHost != null) {
			headers.set("X-Forwarded-Host", forwardedHost);
		}
		return restTemplate.exchange(PROBE, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private void insertDomain(UUID tenantId, String hostname, String status) {
		jdbcTemplate.update(
				"INSERT INTO tenant_custom_domain (id, tenant_id, hostname, status, verification_code, verified_at, "
						+ "created_at, updated_at) VALUES (?, ?, ?, ?, 'code', "
						+ "CASE WHEN ? = 'ACTIVE' THEN now() END, now(), now())",
				UUID.randomUUID(), tenantId, hostname, status, status);
	}

}
