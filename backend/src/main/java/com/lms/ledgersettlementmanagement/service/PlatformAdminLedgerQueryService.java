package com.lms.ledgersettlementmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.ledgersettlementmanagement.web.dto.PlatformLedgerEntryResponse;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
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

	public PlatformAdminLedgerQueryService(LedgerEntryRepository ledgerEntryRepository,
			TenantLookupApi tenantLookupApi) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.tenantLookupApi = tenantLookupApi;
	}

	public Page<PlatformLedgerEntryResponse> getPlatformDashboard(Pageable pageable) {
		requirePlatformAdmin();
		Page<LedgerEntry> page = ledgerEntryRepository.findAllAcrossTenantsForPlatformReport(clampPageSize(pageable));
		Set<UUID> distinctTenantIds = page.getContent()
			.stream()
			.map(LedgerEntry::getTenantId)
			.collect(Collectors.toSet());
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(distinctTenantIds);
		return page.map(entry -> toResponse(entry, tenantSummaries));
	}

	public Page<PlatformLedgerEntryResponse> getTenantDrillDown(UUID tenantId, Pageable pageable) {
		requirePlatformAdmin();
		Map<UUID, TenantSummary> tenantSummaries = resolveTenantSummaries(Set.of(tenantId));
		if (!tenantSummaries.containsKey(tenantId)) {
			throw new NotFoundException("Tenant not found");
		}
		Page<LedgerEntry> page = ledgerEntryRepository.findByTenantIdAcrossTenantsForPlatformReport(tenantId,
				clampPageSize(pageable));
		return page.map(entry -> toResponse(entry, tenantSummaries));
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

	private PlatformLedgerEntryResponse toResponse(LedgerEntry entry, Map<UUID, TenantSummary> tenantSummaries) {
		TenantSummary tenantSummary = tenantSummaries.get(entry.getTenantId());
		return new PlatformLedgerEntryResponse(entry.getId(), entry.getTenantId(),
				tenantSummary == null ? null : tenantSummary.name(), entry.getOrderId(), entry.getPaymentId(),
				entry.getEntryType(), entry.getAmount(), entry.getReversesEntryId(), entry.getCreatedAt());
	}

}
