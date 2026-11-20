package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.tenantmanagement.api.StorefrontHostResolution;
import com.lms.tenantmanagement.config.CustomDomainProperties;
import com.lms.tenantmanagement.repository.TenantCustomDomainRepository;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * STORE-01 / ADR-037 decision 2: only {@code <label>.<subdomain>.<platform
 * domain>} has the storefront shape; one-label tenant hosts, deeper hosts,
 * custom domains and the legacy (no platform domain) mode never do, so their
 * resolution stays exactly as before.
 */
class TenantLookupStorefrontHostTest {

	private final TenantRepository tenantRepository = mock(TenantRepository.class);

	private final TenantCustomDomainRepository customDomainRepository = mock(TenantCustomDomainRepository.class);

	private final CustomDomainResolutionCache cache = mock(CustomDomainResolutionCache.class);

	private TenantLookupService service(String platformDomain) {
		CustomDomainProperties properties = new CustomDomainProperties();
		properties.setPlatformDomain(platformDomain);
		properties.setCnameTarget("tenants." + platformDomain);
		return new TenantLookupService(tenantRepository, customDomainRepository, properties, cache);
	}

	@Test
	void twoLabelsUnderThePlatformDomainAreAStorefrontHost() {
		when(tenantRepository.findBySubdomain(anyString())).thenReturn(Optional.empty());
		Optional<StorefrontHostResolution> resolved = service("lms.test").resolveStorefrontHost("Alice.ABC.lms.test:3000");

		assertThat(resolved).isPresent();
		assertThat(resolved.get().storefrontLabel()).isEqualTo("alice");
		assertThat(resolved.get().tenant()).isNull();
		verify(tenantRepository).findBySubdomain("abc");
	}

	@Test
	void everyOtherHostShapeIsNotAStorefrontHost() {
		TenantLookupService service = service("lms.test");
		assertThat(service.resolveStorefrontHost("abc.lms.test")).isEmpty();
		assertThat(service.resolveStorefrontHost("lms.test")).isEmpty();
		assertThat(service.resolveStorefrontHost("a.b.abc.lms.test")).isEmpty();
		assertThat(service.resolveStorefrontHost(".abc.lms.test")).isEmpty();
		assertThat(service.resolveStorefrontHost("alice.learn.abc.test")).isEmpty();
		assertThat(service.resolveStorefrontHost("alice.abc.lms.test.evil.test")).isEmpty();
		assertThat(service.resolveStorefrontHost(null)).isEmpty();
		assertThat(service("").resolveStorefrontHost("alice.abc.lms.test")).isEmpty();
		verify(tenantRepository, never()).findBySubdomain(anyString());
	}

}
