package com.lms.identityaccessservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantResolution;
import com.lms.tenantmanagement.api.TenantStatus;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEN-DOM-01: the filter resolves the tenant from {@code getServerName()} - the
 * host Tomcat's RemoteIpValve reports, which is {@code X-Forwarded-Host} for a
 * trusted proxy (Next's /api rewrite, Nginx) and the Host header otherwise.
 * The valve itself only runs in a real Tomcat, so here the request simply
 * carries the server name the valve would have set.
 */
class TenantResolutionFilterForwardedHostTest {

	private final TenantLookupApi lookup = mock(TenantLookupApi.class);

	private final TenantResolutionFilter filter = new TenantResolutionFilter(lookup, JsonMapper.builder().build());

	@Test
	void resolvesFromTheProxyReportedServerName() throws Exception {
		UUID tenantId = UUID.randomUUID();
		when(lookup.resolveByHost("learn.abc.test"))
			.thenReturn(Optional.of(new TenantResolution(tenantId, "abc", TenantStatus.ACTIVE)));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/tenant-config/branding");
		request.setServerName("learn.abc.test");
		AtomicReference<Boolean> contextSetInChain = new AtomicReference<>(false);
		FilterChain chain = (req, res) -> contextSetInChain.set(TenantContextHolder.isSet());

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(lookup).resolveByHost("learn.abc.test");
		assertThat(contextSetInChain.get()).isTrue();
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void unresolvedHostIsRejectedWithoutReachingTheChain() throws Exception {
		when(lookup.resolveByHost("unknown.example.org")).thenReturn(Optional.empty());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/tenant-config/branding");
		request.setServerName("unknown.example.org");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<Boolean> reached = new AtomicReference<>(false);

		filter.doFilter(request, response, (req, res) -> reached.set(true));

		assertThat(reached.get()).isFalse();
		assertThat(response.getStatus()).isEqualTo(403);
	}

}
