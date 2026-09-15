package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.auditlogmanagement.web.dto.PlatformAuditLogEntryResponse;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Backs {@code PlatformAdminAuditLogController} (PADASH-2, plan §9.3) - a
 * structurally distinct code path from {@link AuditLogQueryService}: a
 * different class, different repository methods ({@link
 * AuditLogRepository#findAllAcrossTenantsForPlatformReport} for {@link
 * #getPlatformLog}, {@link AuditLogRepository#findByTenantIdAcrossTenantsForPlatformReport}
 * for {@link #getTenantDrillDown} - split so the tenant-scoped drill-down
 * can use a real, sargable {@code tenant_id = :tenantId} predicate instead
 * of degrading to a platform-wide scan; see {@code AuditLogRepository}'s
 * class javadoc), and a simpler authorization check. No {@code
 * VIEWER_ALLOWED_ROLES}-style allowlist is
 * needed here - that mechanism exists because several tenant-scoped roles
 * share one coarse {@code DomainArea}/{@code PermissionAction} grant; Platform
 * Admin has exactly one role and no coarse grant to narrow, so {@code
 * hasRole('PLATFORM_ADMIN')} alone (re-confirmed here at the service layer,
 * independent of the controller's {@code @PreAuthorize}) is the correct,
 * non-redundant gate.
 */
@Service
@Transactional(readOnly = true)
public class PlatformAdminAuditLogQueryService {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminAuditLogQueryService.class);

	/** Defensive server-side cap on page size, mirroring {@code AuditLogQueryService#MAX_PAGE_SIZE}. */
	private static final int MAX_PAGE_SIZE = 100;

	private final AuditLogRepository auditLogRepository;

	private final TenantLookupApi tenantLookupApi;

	private final ObjectMapper objectMapper;

	public PlatformAdminAuditLogQueryService(AuditLogRepository auditLogRepository, TenantLookupApi tenantLookupApi,
			ObjectMapper objectMapper) {
		this.auditLogRepository = auditLogRepository;
		this.tenantLookupApi = tenantLookupApi;
		this.objectMapper = objectMapper;
	}

	public Page<PlatformAuditLogEntryResponse> getPlatformLog(Instant from, Instant to, String action,
			Pageable pageable) {
		requirePlatformAdmin();
		validateRange(from, to);
		Page<AuditLog> page = auditLogRepository.findAllAcrossTenantsForPlatformReport(from, to, action,
				clampPageSize(pageable));
		Set<UUID> distinctTenantIds = page.getContent().stream().map(AuditLog::getTenantId).collect(Collectors.toSet());
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(distinctTenantIds);
		return page.map(auditLog -> toResponse(auditLog, tenantSummaries));
	}

	public Page<PlatformAuditLogEntryResponse> getTenantDrillDown(UUID tenantId, Instant from, Instant to,
			String action, Pageable pageable) {
		requirePlatformAdmin();
		validateRange(from, to);
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(Set.of(tenantId));
		if (!tenantSummaries.containsKey(tenantId)) {
			throw new NotFoundException("Tenant not found");
		}
		Page<AuditLog> page = auditLogRepository.findByTenantIdAcrossTenantsForPlatformReport(tenantId, from, to,
				action, clampPageSize(pageable));
		return page.map(auditLog -> toResponse(auditLog, tenantSummaries));
	}

	private void requirePlatformAdmin() {
		AuthenticatedPrincipalHolder.requireRole("PLATFORM_ADMIN");
	}

	private void validateRange(Instant from, Instant to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidAuditLogSearchException("'from' must not be after 'to'");
		}
	}

	/**
	 * Clamps page size AND strips any {@code Sort} from the incoming {@code
	 * Pageable}. Both {@link AuditLogRepository#findAllAcrossTenantsForPlatformReport}
	 * and {@link AuditLogRepository#findByTenantIdAcrossTenantsForPlatformReport}
	 * are NATIVE queries with their own hardcoded {@code ORDER BY occurred_at
	 * DESC} - for a native {@code @Query}, Spring Data JPA does not translate
	 * a {@code Sort}'s Java property names to real column names the way it
	 * does for JPQL; it appends them to the generated SQL as literal
	 * identifiers. A non-empty {@code Sort} of {@code "occurredAt"} (this
	 * controller's own {@code @PageableDefault}) therefore produced invalid
	 * SQL referencing a column named {@code occurredAt} (no such column -
	 * the real one is {@code occurred_at}), confirmed via a real Postgres
	 * run ("column a.occurredat does not exist"). Passing an unsorted {@code
	 * Pageable} (page/size only) avoids this entirely; ordering is still
	 * fully deterministic via the query's own {@code ORDER BY}.
	 */
	private Pageable clampPageSize(Pageable pageable) {
		int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
		return PageRequest.of(pageable.getPageNumber(), size);
	}

	private PlatformAuditLogEntryResponse toResponse(AuditLog auditLog, Map<UUID, TenantSummary> tenantSummaries) {
		TenantSummary tenantSummary = tenantSummaries.get(auditLog.getTenantId());
		return new PlatformAuditLogEntryResponse(auditLog.getId(), auditLog.getTenantId(),
				tenantSummary == null ? null : tenantSummary.name(), auditLog.getActorId(), auditLog.getAction(),
				auditLog.getTargetEntity(), auditLog.getTargetId(), auditLog.getReason(),
				deserializeMetadata(auditLog.getId(), auditLog.getMetadata()), auditLog.getOccurredAt());
	}

	/**
	 * Batch-resolves tenant display names, mirroring {@code
	 * AuditLogQueryService#resolveActorDisplayNames}'s exact batch pattern -
	 * called once per page (with the page's distinct tenant ids) by {@link
	 * #getPlatformLog}, or once per drill-down call by {@link
	 * #getTenantDrillDown}.
	 */
	private Map<UUID, TenantSummary> resolveTenantSummaries(Set<UUID> tenantIds) {
		return tenantLookupApi.resolveTenantSummaries(tenantIds)
			.stream()
			.collect(Collectors.toMap(TenantSummary::id, s -> s));
	}

	/**
	 * Deliberately more lenient than the tenant-scoped {@code
	 * AuditLogQueryService#deserializeMetadata}, which fails loudly (by
	 * design, per that class's own javadoc) on a row whose {@code metadata}
	 * isn't a JSON object. That "fail loudly" choice is safe at tenant scope
	 * - it can only ever break one tenant's own view of their own data. It is
	 * NOT safe here: this service aggregates every tenant's rows into one
	 * unfiltered platform-wide page, so a single malformed historical row
	 * belonging to ANY one tenant would 500 the entire cross-tenant dashboard
	 * for every Platform Admin, indefinitely - a blast radius the tenant-
	 * scoped version never has. Skip (return {@code null} for that row's
	 * metadata, logging a warning) rather than fail the whole page.
	 */
	private Map<String, Object> deserializeMetadata(UUID auditLogId, String metadata) {
		if (metadata == null) {
			return null;
		}
		try {
			return objectMapper.readValue(metadata, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException e) {
			log.warn("Skipping unparseable metadata for audit log {} in platform-wide view", auditLogId, e);
			return null;
		}
	}

}
