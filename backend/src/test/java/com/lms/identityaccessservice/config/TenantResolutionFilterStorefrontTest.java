package com.lms.identityaccessservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.StorefrontContext;
import com.lms.common.tenant.StorefrontContextHolder;
import com.lms.common.tenant.StorefrontHostResolver;
import com.lms.common.tenant.StorefrontHostResolver.HostLookup;
import com.lms.common.tenant.StorefrontHostResolver.HostStatus;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.tenantmanagement.api.StorefrontHostResolution;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantResolution;
import com.lms.tenantmanagement.api.TenantStatus;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * STORE-01 / ADR-037 decisions 2-3: tenant first (same eligibility, same 403),
 * then the storefront within it; the storefront context is set only for the
 * chain and always cleared; unknown/disabled/retired never reach the chain.
 */
class TenantResolutionFilterStorefrontTest {

	private static final String HOST = "alice.abc.lms.test";

	private final TenantLookupApi lookup = mock(TenantLookupApi.class);

	private final StorefrontHostResolver resolver = mock(StorefrontHostResolver.class);

	private final TenantResolutionFilter filter = new TenantResolutionFilter(lookup, resolver,
			JsonMapper.builder().build());

	private final UUID tenantId = UUID.randomUUID();

	private MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/courses");
		request.setServerName(HOST);
		return request;
	}

	private void tenant(TenantStatus status) {
		when(lookup.resolveStorefrontHost(HOST)).thenReturn(Optional
			.of(new StorefrontHostResolution("alice", new TenantResolution(tenantId, "abc", status))));
	}

	@Test
	void anEnabledStorefrontSetsBothContextsForTheChainOnly() throws Exception {
		tenant(TenantStatus.ACTIVE);
		UUID storefrontId = UUID.randomUUID();
		UUID teacherId = UUID.randomUUID();
		AtomicReference<UUID> tenantSeenByResolver = new AtomicReference<>();
		when(resolver.resolveForHost("alice")).thenAnswer(invocation -> {
			tenantSeenByResolver.set(new com.lms.common.tenant.TenantContextHolder().getTenantId());
			return new HostLookup(HostStatus.FOUND, storefrontId, teacherId, "alice", null);
		});
		AtomicReference<StorefrontContext> seen = new AtomicReference<>();
		AtomicReference<Boolean> tenantSet = new AtomicReference<>(false);

		filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
			seen.set(StorefrontContextHolder.current().orElse(null));
			tenantSet.set(TenantContextHolder.isSet());
		});

		assertThat(tenantSeenByResolver.get()).isEqualTo(tenantId);
		assertThat(seen.get()).isEqualTo(new StorefrontContext(storefrontId, teacherId, "alice"));
		assertThat(tenantSet.get()).isTrue();
		assertThat(StorefrontContextHolder.isSet()).isFalse();
		assertThat(TenantContextHolder.isSet()).isFalse();
		verify(lookup, never()).resolveByHost(anyString());
	}

	@Test
	void unknownOrDisabledStorefrontIs404AndNeverReachesTheChain() throws Exception {
		tenant(TenantStatus.ACTIVE);
		when(resolver.resolveForHost("alice")).thenReturn(HostLookup.notFound());
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<Boolean> reached = new AtomicReference<>(false);

		filter.doFilter(request(), response, (req, res) -> reached.set(true));

		assertThat(reached.get()).isFalse();
		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("STOREFRONT_NOT_FOUND");
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void aRetiredSlugIs404MovedWithTheCurrentHost() throws Exception {
		tenant(TenantStatus.TRIAL);
		when(resolver.resolveForHost("alice"))
			.thenReturn(new HostLookup(HostStatus.MOVED, null, null, "alice-p", "alice-p.abc.lms.test"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request(), response, (req, res) -> {
			throw new AssertionError("chain must not run");
		});

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("STOREFRONT_MOVED").contains("alice-p.abc.lms.test");
	}

	@Test
	void anIneligibleOrUnknownTenantGetsTheGenericTenantAnswerBeforeAnyStorefrontLookup() throws Exception {
		tenant(TenantStatus.SUSPENDED);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(), response, (req, res) -> {
			throw new AssertionError("chain must not run");
		});
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentAsString()).contains("TENANT_UNAVAILABLE");

		when(lookup.resolveStorefrontHost(HOST)).thenReturn(Optional.of(new StorefrontHostResolution("alice", null)));
		MockHttpServletResponse unknown = new MockHttpServletResponse();
		filter.doFilter(request(), unknown, (req, res) -> {
			throw new AssertionError("chain must not run");
		});
		assertThat(unknown.getStatus()).isEqualTo(403);
		verify(resolver, never()).resolveForHost(anyString());
	}

	@Test
	void withoutAResolverStorefrontHostsFailClosed() throws Exception {
		tenant(TenantStatus.ACTIVE);
		TenantResolutionFilter legacy = new TenantResolutionFilter(lookup, JsonMapper.builder().build());
		MockHttpServletResponse response = new MockHttpServletResponse();
		legacy.doFilter(request(), response, (req, res) -> {
			throw new AssertionError("chain must not run");
		});
		assertThat(response.getStatus()).isEqualTo(404);
	}

	@Test
	void aTenantHostNeverSetsAStorefront() throws Exception {
		when(lookup.resolveByHost("abc.lms.test"))
			.thenReturn(Optional.of(new TenantResolution(tenantId, "abc", TenantStatus.ACTIVE)));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/courses");
		request.setServerName("abc.lms.test");
		AtomicReference<Boolean> storefrontSet = new AtomicReference<>(true);

		filter.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> storefrontSet.set(StorefrontContextHolder.isSet()));

		assertThat(storefrontSet.get()).isFalse();
		verify(resolver, never()).resolveForHost(anyString());
	}

}
