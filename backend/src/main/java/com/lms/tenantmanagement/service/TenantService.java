package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.TenantLookupService;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService implements TenantLookupService {

	private final TenantRepository tenantRepository;

	public TenantService(TenantRepository tenantRepository) {
		this.tenantRepository = tenantRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TenantSummary> findActiveTenantBySubdomain(String subdomain) {
		if (subdomain == null || subdomain.isBlank()) {
			return Optional.empty();
		}
		return tenantRepository.findBySubdomain(subdomain)
			.filter(TenantService::isLoginEligible)
			.map(TenantService::toSummary);
	}

	private static boolean isLoginEligible(Tenant tenant) {
		return tenant.getStatus() == TenantStatus.TRIAL || tenant.getStatus() == TenantStatus.ACTIVE;
	}

	private static TenantSummary toSummary(Tenant tenant) {
		return new TenantSummary(tenant.getId(), tenant.getSubdomain(), tenant.getStatus());
	}

}
