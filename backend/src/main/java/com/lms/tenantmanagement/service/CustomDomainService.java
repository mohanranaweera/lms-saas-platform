package com.lms.tenantmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.error.TooManyRequestsException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tenantmanagement.config.CustomDomainProperties;
import com.lms.tenantmanagement.domain.CustomDomainStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.domain.TenantCustomDomain;
import com.lms.tenantmanagement.repository.TenantCustomDomainRepository;
import com.lms.tenantmanagement.repository.TenantRepository;
import com.lms.tenantmanagement.service.DomainOwnershipVerifier.Outcome;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TEN-DOM-01 / ADR-021: tenant custom domains.
 *
 * <ul>
 * <li><b>Tenant Admin</b> (tenant from the resolved TenantContext; permission
 * {@code CUSTOM_DOMAINS} checked by the controller): list, add, verify, make
 * primary, remove.</li>
 * <li><b>Platform Admin</b> (tenant from the admin path): list, switch the
 * feature on/off, suspend a domain.</li>
 * <li><b>Daily re-check</b> of ACTIVE and DNS_BROKEN domains.</li>
 * </ul>
 * DNS lookups never run inside a database transaction (backend rules: no
 * transaction across an external call): state is read, DNS is queried, then
 * the result is applied in a fresh transaction that re-reads the row.
 * Every resolution-affecting change evicts the host cache after commit.
 */
@Service
public class CustomDomainService {

	private static final Logger log = LoggerFactory.getLogger(CustomDomainService.class);

	private static final String TARGET = "tenant_custom_domain";

	private static final String VERIFY_RATE_KEY = "custom-domain:verify:";

	private static final Duration VERIFY_RATE_WINDOW = Duration.ofHours(1);

	private static final EnumSet<CustomDomainStatus> RECHECKED = EnumSet.of(CustomDomainStatus.ACTIVE,
			CustomDomainStatus.DNS_BROKEN);

	private static final SecureRandom RANDOM = new SecureRandom();

	public record DnsRecordView(String type, String name, String value) {

	}

	public record CustomDomainView(UUID id, String hostname, CustomDomainStatus status, boolean primary,
			List<DnsRecordView> dnsRecords, Instant verifiedAt, Instant lastCheckedAt, String lastError,
			String suspendedReason, Instant createdAt) {

	}

	/**
	 * @param enabled the Platform Admin switch for this tenant
	 * @param available the platform has custom domains configured at all
	 */
	public record CustomDomainsOverview(boolean enabled, boolean available, int maxDomains,
			List<CustomDomainView> domains) {

	}

	private final TenantCustomDomainRepository domains;

	private final TenantRepository tenants;

	private final CustomDomainProperties properties;

	private final DomainOwnershipVerifier verifier;

	private final CustomDomainResolutionCache cache;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final StringRedisTemplate redis;

	private final TransactionTemplate writeTx;

	private final TransactionTemplate readTx;

	public CustomDomainService(TenantCustomDomainRepository domains, TenantRepository tenants,
			CustomDomainProperties properties, DomainOwnershipVerifier verifier, CustomDomainResolutionCache cache,
			AuditLogApi auditLogApi, TenantContext tenantContext, StringRedisTemplate redis,
			PlatformTransactionManager transactionManager) {
		this.domains = domains;
		this.tenants = tenants;
		this.properties = properties;
		this.verifier = verifier;
		this.cache = cache;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.redis = redis;
		this.writeTx = new TransactionTemplate(transactionManager);
		this.readTx = new TransactionTemplate(transactionManager);
		this.readTx.setReadOnly(true);
	}

	// ---------------------------------------------------------------- Tenant Admin

	@Transactional(readOnly = true)
	public CustomDomainsOverview overview() {
		return overviewOf(currentTenant());
	}

	@Transactional
	public CustomDomainView add(String rawHostname) {
		Tenant tenant = currentTenant();
		requireUsable(tenant);
		String hostname = Hostnames.normalise(rawHostname);
		Hostnames.problem(hostname, properties.getPlatformDomain()).ifPresent(problem -> {
			throw new FieldValidationException("Invalid domain", List.of(new FieldError("hostname", problem)));
		});
		if (domains.countNotRemoved() >= properties.getMaxPerTenant()) {
			throw CustomDomainException.limitReached(properties.getMaxPerTenant());
		}
		if (domains.existsClaimedHostnameAcrossTenants(hostname)) {
			throw CustomDomainException.taken();
		}
		TenantCustomDomain saved;
		try {
			saved = domains.saveAndFlush(TenantCustomDomain.claim(tenant.getId(), hostname, newVerificationCode()));
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race for the same hostname (uq_tenant_custom_domain_hostname).
			throw CustomDomainException.taken();
		}
		audit(saved, "custom_domain.added", null);
		return view(saved);
	}

	/** Checks DNS now. Not transactional: the lookup runs between two short transactions. */
	public CustomDomainView verify(UUID domainId) {
		UUID tenantId = tenantContext.getTenantId();
		TenantCustomDomain domain = readTx.execute(status -> {
			requireUsable(currentTenant());
			TenantCustomDomain found = findLive(domainId);
			requireNotSuspended(found);
			return found;
		});
		if (!tryAcquireVerifyAttempt(tenantId)) {
			throw new TooManyRequestsException("Too many verification attempts. Try again later.");
		}
		Outcome outcome = verifier.verify(domain.getHostname(), domain.getVerificationCode());
		return writeTx.execute(status -> {
			TenantCustomDomain fresh = findLive(domainId);
			requireNotSuspended(fresh);
			CustomDomainStatus before = fresh.getStatus();
			applyOutcome(fresh, outcome);
			if (before != CustomDomainStatus.ACTIVE && fresh.getStatus() == CustomDomainStatus.ACTIVE) {
				audit(fresh, "custom_domain.verified", null);
			}
			else if (before == CustomDomainStatus.ACTIVE && fresh.getStatus() == CustomDomainStatus.DNS_BROKEN) {
				audit(fresh, "custom_domain.dns_broken", null);
			}
			return view(fresh);
		});
	}

	@Transactional
	public CustomDomainView makePrimary(UUID domainId) {
		requireUsable(currentTenant());
		TenantCustomDomain domain = findLive(domainId);
		if (domain.getStatus() != CustomDomainStatus.ACTIVE) {
			throw new ConflictException("Only a verified domain can be the primary domain.");
		}
		if (domain.isPrimary()) {
			return view(domain);
		}
		domains.findCurrentPrimary().ifPresent(previous -> {
			previous.clearPrimary();
			// Flushed first: uq_tenant_custom_domain_primary allows one primary.
			domains.saveAndFlush(previous);
		});
		domain.makePrimary();
		TenantCustomDomain saved = domains.save(domain);
		audit(saved, "custom_domain.primary_set", null);
		return view(saved);
	}

	/** Allowed while the feature is switched off, so a tenant can always clean up. */
	@Transactional
	public void remove(UUID domainId) {
		TenantCustomDomain domain = findLive(domainId);
		requireNotSuspended(domain);
		domain.remove();
		domains.save(domain);
		cache.evictAfterCommit(domain.getHostname());
		audit(domain, "custom_domain.removed", null);
	}

	// ---------------------------------------------------------------- Platform Admin

	@Transactional(readOnly = true)
	public CustomDomainsOverview overviewForTenant(UUID tenantId) {
		return overviewOf(tenantOrNotFound(tenantId));
	}

	@Transactional
	public CustomDomainsOverview setEnabled(UUID tenantId, boolean enabled) {
		Tenant tenant = tenants.findByIdForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Tenant not found"));
		if (tenant.isCustomDomainsEnabled() != enabled) {
			tenant.setCustomDomainsEnabled(enabled);
			tenants.save(tenant);
			cache.evictAfterCommit(domains.findHostnamesForTenantAcrossTenants(tenantId));
			auditLogApi.recordForTenant(tenantId, AuditLogEntry.of(actorId(),
					enabled ? "tenant.custom_domains_enabled" : "tenant.custom_domains_disabled", "tenant", tenantId));
		}
		return overviewOf(tenant);
	}

	@Transactional
	public CustomDomainView suspend(UUID tenantId, UUID domainId, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new FieldValidationException("A reason is required", List.of(new FieldError("reason", "is required")));
		}
		tenantOrNotFound(tenantId);
		TenantCustomDomain domain = inTenant(tenantId, () -> {
			TenantCustomDomain found = findLive(domainId);
			if (found.getStatus() == CustomDomainStatus.SUSPENDED) {
				throw new ConflictException("This domain is already suspended.");
			}
			found.suspend(reason.trim());
			return domains.save(found);
		});
		cache.evictAfterCommit(domain.getHostname());
		// recordForTenant refuses to run while a TenantContext is set, so after inTenant.
		auditLogApi.recordForTenant(tenantId, new AuditLogEntry(actorId(), "custom_domain.suspended", TARGET,
				domain.getId(), reason.trim(), Map.of("hostname", domain.getHostname())));
		return view(domain);
	}

	// ---------------------------------------------------------------- Daily re-check

	/**
	 * Re-checks every ACTIVE and DNS_BROKEN domain. A missing record breaks an
	 * ACTIVE domain; records back restore a DNS_BROKEN one; a lookup that can't
	 * complete changes nothing. Automatic, so logged rather than audited (no
	 * acting user).
	 */
	public void recheckAll() {
		if (!properties.isAvailable()) {
			return;
		}
		List<UUID> ids = readTx.execute(status -> domains.findIdsByStatusInAcrossTenants(RECHECKED));
		for (UUID id : ids) {
			try {
				recheck(id);
			}
			catch (RuntimeException ex) {
				log.atError()
					.setMessage("custom_domain.recheck_failed")
					.addKeyValue("domainId", id)
					.addKeyValue("errorType", ex.getClass().getSimpleName())
					.log();
			}
		}
	}

	void recheck(UUID domainId) {
		TenantCustomDomain domain = readTx.execute(status -> domains.findByIdAcrossTenants(domainId).orElse(null));
		if (domain == null || !RECHECKED.contains(domain.getStatus())) {
			return;
		}
		Outcome outcome = verifier.verify(domain.getHostname(), domain.getVerificationCode());
		inTenant(domain.getTenantId(), () -> writeTx.execute(status -> {
			TenantCustomDomain fresh = domains.findById(domainId).orElse(null);
			if (fresh == null || !RECHECKED.contains(fresh.getStatus())) {
				return null;
			}
			CustomDomainStatus before = fresh.getStatus();
			applyOutcome(fresh, outcome);
			if (before != fresh.getStatus()) {
				log.atWarn()
					.setMessage("custom_domain.status_changed")
					.addKeyValue("tenantId", fresh.getTenantId())
					.addKeyValue("hostname", fresh.getHostname())
					.addKeyValue("from", before)
					.addKeyValue("to", fresh.getStatus())
					.log();
			}
			return null;
		}));
	}

	// ---------------------------------------------------------------- helpers

	private void applyOutcome(TenantCustomDomain domain, Outcome outcome) {
		CustomDomainStatus before = domain.getStatus();
		Instant now = Instant.now();
		switch (outcome.result()) {
			case VERIFIED -> domain.markVerified(now);
			case NOT_VERIFIED -> domain.markCheckFailed(outcome.message(), now);
			case INCONCLUSIVE -> domain.markCheckInconclusive(outcome.message(), now);
		}
		domains.save(domain);
		if (before != domain.getStatus()) {
			cache.evictAfterCommit(domain.getHostname());
		}
	}

	private CustomDomainsOverview overviewOf(Tenant tenant) {
		List<CustomDomainView> list = inTenant(tenant.getId(),
				() -> domains.findAllNotRemoved().stream().map(this::view).toList());
		return new CustomDomainsOverview(tenant.isCustomDomainsEnabled(), properties.isAvailable(),
				properties.getMaxPerTenant(), list);
	}

	private Tenant currentTenant() {
		return tenantOrNotFound(tenantContext.getTenantId());
	}

	private Tenant tenantOrNotFound(UUID tenantId) {
		return tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant not found"));
	}

	private void requireUsable(Tenant tenant) {
		if (!properties.isAvailable()) {
			throw new ServiceUnavailableException("Custom domains aren't available on this platform yet.");
		}
		if (!tenant.isCustomDomainsEnabled()) {
			throw CustomDomainException.disabled();
		}
	}

	/** Tenant-scoped (TenantContext) - another tenant's id is simply not found. */
	private TenantCustomDomain findLive(UUID domainId) {
		return domains.findById(domainId)
			.filter(domain -> domain.getStatus() != CustomDomainStatus.REMOVED)
			.orElseThrow(() -> new NotFoundException("Custom domain not found"));
	}

	private static void requireNotSuspended(TenantCustomDomain domain) {
		if (domain.getStatus() == CustomDomainStatus.SUSPENDED) {
			throw new ConflictException("This domain was suspended by the platform team. Contact them to restore it.");
		}
	}

	private boolean tryAcquireVerifyAttempt(UUID tenantId) {
		String key = VERIFY_RATE_KEY + tenantId;
		try {
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, VERIFY_RATE_WINDOW);
			}
			return count == null || count <= properties.getVerifyAttemptsPerHour();
		}
		catch (RuntimeException ex) {
			// Redis is a cache/limit layer only; fail open like AuthRateLimiter.
			return true;
		}
	}

	private void audit(TenantCustomDomain domain, String action, String reason) {
		auditLogApi.record(new AuditLogEntry(actorId(), action, TARGET, domain.getId(), reason,
				Map.of("hostname", domain.getHostname())));
	}

	private static UUID actorId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	private CustomDomainView view(TenantCustomDomain domain) {
		String target = properties.getCnameTarget();
		List<DnsRecordView> records = List.of(
				new DnsRecordView("CNAME", domain.getHostname(), target.isEmpty() ? null : target),
				new DnsRecordView("TXT", DomainOwnershipVerifier.txtRecordName(domain.getHostname()),
						domain.getVerificationCode()));
		return new CustomDomainView(domain.getId(), domain.getHostname(), domain.getStatus(), domain.isPrimary(),
				records, domain.getVerifiedAt(), domain.getLastCheckedAt(), domain.getLastError(),
				domain.getSuspendedReason(), domain.getCreatedAt());
	}

	private static String newVerificationCode() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	/**
	 * Runs {@code work} as {@code tenantId} (Platform Admin paths and the
	 * re-check job carry the tenant explicitly - tenancy rules on background
	 * work), restoring whatever context was set before.
	 */
	private <T> T inTenant(UUID tenantId, Supplier<T> work) {
		UUID previous = TenantContextHolder.isSet() ? tenantContext.getTenantId() : null;
		TenantContextHolder.set(tenantId);
		try {
			return work.get();
		}
		finally {
			if (previous == null) {
				TenantContextHolder.clear();
			}
			else {
				TenantContextHolder.set(previous);
			}
		}
	}

}
