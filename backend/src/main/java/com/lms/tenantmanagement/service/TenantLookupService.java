package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantResolution;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code tenant-management}'s half of {@code TEN-3}: a narrow subdomain ->
 * tenant lookup. Applies no fallback/default-tenant behavior - absence is
 * absence, returned as {@link Optional#empty()}. Does not touch
 * {@code TenantContextHolder} and implements no
 * {@code SecurityFilterChain}/servlet filter; the actual HTTP-layer
 * resolution mechanism belongs to {@code identity-access-service} (see
 * {@link TenantLookupApi}'s doc comment).
 */
@Service
@Transactional(readOnly = true)
public class TenantLookupService implements TenantLookupApi {

	private final TenantRepository tenantRepository;

	public TenantLookupService(TenantRepository tenantRepository) {
		this.tenantRepository = tenantRepository;
	}

	@Override
	public Optional<TenantResolution> resolveBySubdomain(String subdomain) {
		return tenantRepository.findBySubdomain(subdomain).map(TenantLookupService::toResolution);
	}

	private static TenantResolution toResolution(Tenant tenant) {
		return new TenantResolution(tenant.getId(), tenant.getSubdomain(), tenant.getStatus());
	}

}
