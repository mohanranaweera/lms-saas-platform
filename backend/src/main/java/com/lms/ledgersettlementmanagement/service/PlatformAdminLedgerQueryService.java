package com.lms.ledgersettlementmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.ledgersettlementmanagement.web.dto.PlatformLedgerEntryResponse;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code PlatformAdminLedgerController} (PADASH-2, plan §9.2) - a
 * cross-tenant, ledger-derived (never {@code payment.status}/{@code order}-
 * derived, per {@code .claude/rules/payments.md} §1) read-only dashboard,
 * sibling to {@code LedgerQueryService} but structurally distinct: its own
 * class, its own repository methods ({@link
 * LedgerEntryRepository#findAllAcrossTenantsForPlatformReport} for {@link
 * #getPlatformDashboard}, {@link
 * LedgerEntryRepository#findByTenantIdAcrossTenantsForPlatformReport} for
 * {@link #getTenantDrillDown} - kept as two explicit, fixed-shape queries
 * rather than one nullable-{@code tenantId} query, for consistency with
 * {@code AuditLogRepository}'s equivalent split and to remove any
 * prepared-statement plan-cache ambiguity; see {@code
 * LedgerEntryRepository}'s class javadoc), its own (Platform-Admin-only)
 * authorization check.
 */
@Service
@Transactional(readOnly = true)
public class PlatformAdminLedgerQueryService {

	/** Defensive server-side cap on page size, mirroring {@code AuditLogQueryService#MAX_PAGE_SIZE}. */
	private static final int MAX_PAGE_SIZE = 100;

	private final LedgerEntryRepository ledgerEntryRepository;

	private final TenantLookupApi tenantLookupApi;

	private final LedgerViewEnrichmentService ledgerViewEnrichmentService;

	public PlatformAdminLedgerQueryService(LedgerEntryRepository ledgerEntryRepository,
			TenantLookupApi tenantLookupApi, LedgerViewEnrichmentService ledgerViewEnrichmentService) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.tenantLookupApi = tenantLookupApi;
		this.ledgerViewEnrichmentService = ledgerViewEnrichmentService;
	}

	public Page<PlatformLedgerEntryResponse> getPlatformDashboard(Pageable pageable) {
		return getPlatformDashboard(pageable, null, null);
	}

	/**
	 * @param status/@param method optional Wave 6 §4 filters, same "{@code
	 * null} = unfiltered" contract as {@code LedgerController#getDashboard}.
	 * When BOTH filters are {@code null}, this delegates to the original,
	 * DB-paginated {@link LedgerEntryRepository#findAllAcrossTenantsForPlatformReport}
	 * path (unchanged behavior/cost). When either filter is supplied, this
	 * instead reads every entry platform-wide via {@link
	 * LedgerEntryRepository#findAllAcrossTenantsForPlatformReportUnpaged()},
	 * enriches+filters in application code, then paginates the FILTERED list
	 * itself - mirroring {@code LedgerQueryService#getDashboard}'s identical
	 * tenant-scoped tradeoff (see that method's javadoc), so {@code
	 * totalElements}/{@code totalPages} always reflect the actual filtered
	 * result set rather than the unfiltered DB count. Acceptable at current
	 * data volumes per plan §10 judgment call 3; a materialized cross-tenant
	 * read model is the correct future direction if this needs to scale
	 * further.
	 */
	public Page<PlatformLedgerEntryResponse> getPlatformDashboard(Pageable pageable, PaymentOperationalState status,
			PaymentMethod method) {
		requirePlatformAdmin();
		Pageable clamped = clampPageSize(pageable);
		if (status == null && method == null) {
			Page<LedgerEntry> page = ledgerEntryRepository.findAllAcrossTenantsForPlatformReport(clamped);
			return toResponsePage(page);
		}
		List<LedgerEntry> allEntries = ledgerEntryRepository.findAllAcrossTenantsForPlatformReportUnpaged();
		return toFilteredPaginatedResponse(allEntries, clamped, status, method);
	}

	public Page<PlatformLedgerEntryResponse> getTenantDrillDown(UUID tenantId, Pageable pageable) {
		return getTenantDrillDown(tenantId, pageable, null, null);
	}

	/** @param status/@param method see {@link #getPlatformDashboard(Pageable, PaymentOperationalState, PaymentMethod)}'s javadoc. */
	public Page<PlatformLedgerEntryResponse> getTenantDrillDown(UUID tenantId, Pageable pageable,
			PaymentOperationalState status, PaymentMethod method) {
		requirePlatformAdmin();
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(Set.of(tenantId));
		if (!tenantSummaries.containsKey(tenantId)) {
			throw new NotFoundException("Tenant not found");
		}
		Pageable clamped = clampPageSize(pageable);
		if (status == null && method == null) {
			Page<LedgerEntry> page = ledgerEntryRepository.findByTenantIdAcrossTenantsForPlatformReport(tenantId,
					clamped);
			return toResponsePage(page);
		}
		List<LedgerEntry> allEntries = ledgerEntryRepository
			.findByTenantIdAcrossTenantsForPlatformReportUnpaged(tenantId);
		return toFilteredPaginatedResponse(allEntries, clamped, status, method);
	}

	/** Unfiltered path: builds a response page directly from an already-DB-paginated page, preserving its pagination metadata. */
	private Page<PlatformLedgerEntryResponse> toResponsePage(Page<LedgerEntry> page) {
		Set<UUID> distinctTenantIds = page.getContent()
			.stream()
			.map(LedgerEntry::getTenantId)
			.collect(Collectors.toSet());
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(distinctTenantIds);
		Map<UUID, LedgerHistoryEntryView> enrichedById = enrichAcrossTenants(page.getContent());
		List<PlatformLedgerEntryResponse> content = page.getContent()
			.stream()
			.map(entry -> toResponse(entry, tenantSummaries, enrichedById.get(entry.getId())))
			.toList();
		return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
	}

	/**
	 * Filtered path: {@code allEntries} is the FULL (unpaged) entry set to
	 * filter over - filters, builds responses, then paginates the FILTERED
	 * list itself so {@code totalElements}/{@code totalPages} are correct
	 * for the filtered result set (see {@link #getPlatformDashboard}'s
	 * javadoc).
	 */
	private Page<PlatformLedgerEntryResponse> toFilteredPaginatedResponse(List<LedgerEntry> allEntries,
			Pageable pageable, PaymentOperationalState status, PaymentMethod method) {
		Set<UUID> distinctTenantIds = allEntries.stream().map(LedgerEntry::getTenantId).collect(Collectors.toSet());
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(distinctTenantIds);
		Map<UUID, LedgerHistoryEntryView> enrichedById = enrichAcrossTenants(allEntries);
		List<PlatformLedgerEntryResponse> filtered = new ArrayList<>();
		for (LedgerEntry entry : allEntries) {
			LedgerHistoryEntryView enriched = enrichedById.get(entry.getId());
			if (status != null && (enriched == null || status != enriched.operationalState())) {
				continue;
			}
			if (method != null && (enriched == null || method != enriched.method())) {
				continue;
			}
			filtered.add(toResponse(entry, tenantSummaries, enriched));
		}
		return paginate(filtered, pageable);
	}

	private static Page<PlatformLedgerEntryResponse> paginate(List<PlatformLedgerEntryResponse> items,
			Pageable pageable) {
		int start = (int) pageable.getOffset();
		if (start >= items.size()) {
			return new PageImpl<>(List.of(), pageable, items.size());
		}
		int end = Math.min(start + pageable.getPageSize(), items.size());
		return new PageImpl<>(items.subList(start, end), pageable, items.size());
	}

	/**
	 * Wave 6 §4 - {@link LedgerViewEnrichmentService}/{@code
	 * PaymentStatusApi}/{@code SlipStatusApi} are all tenant-scoped through
	 * {@link com.lms.common.tenant.TenantContext} (structurally, via {@code
	 * TenantAwareRepository}), but this platform-wide page can span MANY
	 * tenants at once and platform-admin requests never resolve a tenant
	 * context of their own. Groups the page's entries by their OWN {@code
	 * tenantId} (never a client-supplied one), then enriches each group
	 * with {@link TenantContextHolder} explicitly set to THAT group's
	 * trusted tenant id for the duration of the call - mirroring {@code
	 * PaymentConfirmationService}'s identical established
	 * set-in-try/clear-in-finally technique for the same underlying reason
	 * (no ambient tenant context on this request path). Never leaks one
	 * tenant's data into another group's enrichment call, since each
	 * group's {@code orderIds} only ever contains that same tenant's own
	 * entries.
	 */
	private Map<UUID, LedgerHistoryEntryView> enrichAcrossTenants(List<LedgerEntry> entries) {
		Map<UUID, List<LedgerEntry>> entriesByTenantId = entries.stream()
			.collect(Collectors.groupingBy(LedgerEntry::getTenantId));
		Map<UUID, LedgerHistoryEntryView> enrichedById = new HashMap<>();
		for (Map.Entry<UUID, List<LedgerEntry>> tenantGroup : entriesByTenantId.entrySet()) {
			List<LedgerHistoryEntryView> rawViews = tenantGroup.getValue().stream().map(PlatformAdminLedgerQueryService::toRawView).toList();
			try {
				TenantContextHolder.set(tenantGroup.getKey());
				for (LedgerHistoryEntryView enriched : ledgerViewEnrichmentService.enrich(rawViews)) {
					enrichedById.put(enriched.id(), enriched);
				}
			}
			finally {
				TenantContextHolder.clear();
			}
		}
		return enrichedById;
	}

	private static LedgerHistoryEntryView toRawView(LedgerEntry entry) {
		return new LedgerHistoryEntryView(entry.getId(), entry.getOrderId(), entry.getPaymentId(),
				entry.getEntryType(), entry.getAmount(), entry.getReversesEntryId(), entry.getCreatedAt());
	}

	private void requirePlatformAdmin() {
		AuthenticatedPrincipalHolder.requireRole("PLATFORM_ADMIN");
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	private Map<UUID, TenantSummary> resolveTenantSummaries(Set<UUID> tenantIds) {
		return tenantLookupApi.resolveTenantSummaries(tenantIds)
			.stream()
			.collect(Collectors.toMap(TenantSummary::id, s -> s));
	}

	private PlatformLedgerEntryResponse toResponse(LedgerEntry entry, Map<UUID, TenantSummary> tenantSummaries,
			LedgerHistoryEntryView enriched) {
		TenantSummary tenantSummary = tenantSummaries.get(entry.getTenantId());
		String tenantName = (tenantSummary == null) ? null : tenantSummary.name();
		if (enriched != null) {
			return PlatformLedgerEntryResponse.from(enriched, entry.getTenantId(), tenantName);
		}
		// Defensive fallback (should be structurally unreachable - every
		// entry passed through enrichAcrossTenants) - unenriched rather
		// than dropping the row entirely.
		return new PlatformLedgerEntryResponse(entry.getId(), entry.getTenantId(), tenantName, entry.getOrderId(),
				entry.getPaymentId(), entry.getEntryType(), entry.getAmount(), entry.getReversesEntryId(),
				entry.getCreatedAt(), null, null, null, null, null, null);
	}

}
