package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantResolution;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.tenantmanagement.config.CustomDomainProperties;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.domain.TenantCustomDomain;
import com.lms.tenantmanagement.repository.TenantCustomDomainRepository;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

	private final TenantCustomDomainRepository customDomainRepository;

	private final CustomDomainProperties customDomainProperties;

	private final CustomDomainResolutionCache resolutionCache;

	public TenantLookupService(TenantRepository tenantRepository, TenantCustomDomainRepository customDomainRepository,
			CustomDomainProperties customDomainProperties, CustomDomainResolutionCache resolutionCache) {
		this.tenantRepository = tenantRepository;
		this.customDomainRepository = customDomainRepository;
		this.customDomainProperties = customDomainProperties;
		this.resolutionCache = resolutionCache;
	}

	@Override
	public Optional<TenantResolution> resolveByHost(String hostHeader) {
		String host = Hostnames.requestHost(hostHeader);
		if (host == null) {
			return Optional.empty();
		}
		String platformDomain = customDomainProperties.getPlatformDomain();
		if (platformDomain.isEmpty()) {
			// Legacy: no platform domain configured, so the first label is the
			// subdomain and custom domains can't be told apart - never resolved.
			int firstDot = host.indexOf('.');
			return (firstDot <= 0) ? Optional.empty() : resolveBySubdomain(host.substring(0, firstDot));
		}
		if (host.endsWith("." + platformDomain)) {
			String label = host.substring(0, host.length() - platformDomain.length() - 1);
			return (label.isEmpty() || label.contains(".")) ? Optional.empty() : resolveBySubdomain(label);
		}
		if (host.equals(platformDomain) || !customDomainProperties.isAvailable()) {
			return Optional.empty();
		}
		return resolveCustomDomain(host);
	}

	/**
	 * ACTIVE custom domain -> tenant (cached, see {@link
	 * CustomDomainResolutionCache}). The tenant row itself is always read fresh,
	 * so its status and the enabled switch are never stale.
	 */
	private Optional<TenantResolution> resolveCustomDomain(String host) {
		Optional<CustomDomainResolutionCache.Hit> cached = resolutionCache.get(host);
		UUID tenantId;
		if (cached.isPresent()) {
			// May be null: a cached "no such domain".
			tenantId = cached.get().tenantId();
		}
		else {
			tenantId = customDomainRepository.findResolvableByHostnameAcrossTenants(host)
				.map(TenantCustomDomain::getTenantId)
				.orElse(null);
			resolutionCache.put(host, tenantId);
		}
		if (tenantId == null) {
			return Optional.empty();
		}
		return tenantRepository.findById(tenantId)
			.filter(Tenant::isCustomDomainsEnabled)
			.map(TenantLookupService::toResolution);
	}

	@Override
	public Optional<String> tenantOrigin(UUID tenantId, String urlTemplate) {
		if (urlTemplate == null || urlTemplate.isBlank()) {
			return Optional.empty();
		}
		return tenantRepository.findById(tenantId).map(tenant -> {
			if (tenant.isCustomDomainsEnabled() && customDomainProperties.isAvailable()) {
				Optional<String> primary = customDomainRepository.findPrimaryActiveForTenantAcrossTenants(tenantId)
					.map(TenantCustomDomain::getHostname);
				if (primary.isPresent()) {
					return Hostnames.withHost(urlTemplate, primary.get());
				}
			}
			return urlTemplate.replace("{subdomain}", tenant.getSubdomain());
		});
	}

	@Override
	public Optional<TenantResolution> resolveBySubdomain(String subdomain) {
		return tenantRepository.findBySubdomain(subdomain).map(TenantLookupService::toResolution);
	}

	@Override
	public Optional<String> findSubdomain(UUID tenantId) {
		return tenantRepository.findById(tenantId).map(Tenant::getSubdomain);
	}

	@Override
	public List<TenantSummary> resolveTenantSummaries(Set<UUID> tenantIds) {
		if (tenantIds == null || tenantIds.isEmpty()) {
			return List.of();
		}
		return tenantRepository.findAllById(tenantIds).stream().map(TenantLookupService::toSummary).toList();
	}

	private static TenantResolution toResolution(Tenant tenant) {
		return new TenantResolution(tenant.getId(), tenant.getSubdomain(), tenant.getStatus());
	}

	private static TenantSummary toSummary(Tenant tenant) {
		return new TenantSummary(tenant.getId(), tenant.getName(), tenant.getStatus());
	}

}
