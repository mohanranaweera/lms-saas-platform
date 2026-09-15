package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.ledgersettlementmanagement.web.dto.PlatformLedgerEntryResponse;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.api.TenantSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Plain Mockito unit test directly against {@link PlatformAdminLedgerQueryService},
 * mirroring {@code AuditLogQueryServiceTest}'s established style for this
 * layer. Closes plan §18 unit-test item 2's "{@code
 * PlatformLedgerEntryResponse} shape test" - preferred here as an assertion
 * on the real mapping method ({@code toResponse}, exercised through the
 * public {@code getPlatformDashboard}/{@code getTenantDrillDown} entry
 * points) rather than a bare construction test on the record type itself -
 * {@code PlatformLedgerEntryResponse}'s own compact-constructor null-checks
 * are covered separately by {@code PlatformLedgerEntryResponseTest}; the
 * assertions here instead prove the mapping at this real call site never
 * feeds it a null {@code tenantId}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformAdminLedgerQueryServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private LedgerEntryRepository ledgerEntryRepository;

	@Mock
	private TenantLookupApi tenantLookupApi;

	private PlatformAdminLedgerQueryService service;

	@BeforeEach
	void setUp() {
		service = new PlatformAdminLedgerQueryService(ledgerEntryRepository, tenantLookupApi);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), null, "PLATFORM_ADMIN", UUID.randomUUID()));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	/**
	 * H5: proves {@link PlatformAdminLedgerQueryService} is a genuinely
	 * separate class from its tenant-scoped sibling {@link
	 * LedgerQueryService} - not a subtype, not the same class reused with a
	 * flag - mirroring {@code PlatformAuditLogRepositoryPathAppendOnlyTest}'s
	 * reflection/identity-check style for the analogous
	 * repository-bypass-method proof.
	 */
	@Test
	void isAStructurallyDistinctClassFromTheTenantScopedLedgerQueryService() {
		assertThat(PlatformAdminLedgerQueryService.class).isNotEqualTo(LedgerQueryService.class);
		assertThat(LedgerQueryService.class.isAssignableFrom(PlatformAdminLedgerQueryService.class)).isFalse();
		assertThat(PlatformAdminLedgerQueryService.class.isAssignableFrom(LedgerQueryService.class)).isFalse();
	}

	/**
	 * H5: proves the platform-admin dashboard path calls the explicit,
	 * reviewed cross-tenant bypass method ({@code
	 * findAllAcrossTenantsForPlatformReport}) and never the tenant-scoped
	 * dashboard read ({@code findAllForDashboard}) that {@link
	 * LedgerQueryService} uses (via {@code LedgerEntryApi}) for its own,
	 * tenant-admin-facing dashboard - a Mockito {@code verify(never())}-based
	 * proof that these two read paths cannot be accidentally conflated.
	 */
	@Test
	void getPlatformDashboardCallsOnlyTheAcrossTenantsRepositoryMethodNeverTheTenantScopedDashboardMethod() {
		Pageable pageable = PageRequest.of(0, 20);
		when(ledgerEntryRepository.findAllAcrossTenantsForPlatformReport(any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		service.getPlatformDashboard(pageable);

		verify(ledgerEntryRepository).findAllAcrossTenantsForPlatformReport(any());
		verify(ledgerEntryRepository, never()).findAllForDashboard(any());
		verify(ledgerEntryRepository, never()).findByTenantIdAcrossTenantsForPlatformReport(any(), any());
	}

	/**
	 * H5 follow-up (post-review split of the platform-report repository
	 * method into two, per {@code LedgerEntryRepository}'s class javadoc):
	 * the single-tenant drill-down must call the fixed-shape,
	 * tenant-scoped repository method - never the unfiltered platform-wide
	 * one - so it cannot silently degrade back into a platform-wide scan or
	 * become a prepared-statement plan-cache hazard.
	 */
	@Test
	void getTenantDrillDownCallsOnlyTheTenantScopedRepositoryMethodNeverThePlatformWideOne() {
		Pageable pageable = PageRequest.of(0, 20);
		when(tenantLookupApi.resolveTenantSummaries(Set.of(TENANT_ID)))
			.thenReturn(List.of(new TenantSummary(TENANT_ID, "Acme Institute", TenantStatus.ACTIVE)));
		when(ledgerEntryRepository.findByTenantIdAcrossTenantsForPlatformReport(eq(TENANT_ID), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		service.getTenantDrillDown(TENANT_ID, pageable);

		verify(ledgerEntryRepository).findByTenantIdAcrossTenantsForPlatformReport(eq(TENANT_ID), any());
		verify(ledgerEntryRepository, never()).findAllAcrossTenantsForPlatformReport(any());
	}

	@Test
	void everyRowInThePlatformDashboardCarriesANonNullTenantIdEvenWhenTheTenantNameResolves() {
		LedgerEntry entry = new LedgerEntry(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(),
				LedgerEntryType.PAYMENT_CONFIRMED, new BigDecimal("99.99"), null);
		Pageable pageable = PageRequest.of(0, 20);
		Page<LedgerEntry> page = new PageImpl<>(List.of(entry), pageable, 1);
		when(ledgerEntryRepository.findAllAcrossTenantsForPlatformReport(any())).thenReturn(page);
		when(tenantLookupApi.resolveTenantSummaries(Set.of(TENANT_ID)))
			.thenReturn(List.of(new TenantSummary(TENANT_ID, "Acme Institute", TenantStatus.ACTIVE)));

		Page<PlatformLedgerEntryResponse> result = service.getPlatformDashboard(pageable);

		assertThat(result.getContent()).hasSize(1);
		PlatformLedgerEntryResponse row = result.getContent().get(0);
		assertThat(row.tenantId()).isEqualTo(TENANT_ID);
		assertThat(row.tenantName()).isEqualTo("Acme Institute");
	}

	/**
	 * {@code tenantName} is best-effort (falls back to {@code null} per the
	 * DTO's own javadoc if the tenant id no longer resolves), but {@code
	 * tenantId} itself must never be null regardless - this proves the two
	 * fields are independently guaranteed, not accidentally coupled.
	 */
	@Test
	void tenantIdIsStillPopulatedWhenTheTenantNameFailsToResolve() {
		LedgerEntry entry = new LedgerEntry(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(),
				LedgerEntryType.PAYMENT_CONFIRMED, new BigDecimal("50.00"), null);
		Pageable pageable = PageRequest.of(0, 20);
		Page<LedgerEntry> page = new PageImpl<>(List.of(entry), pageable, 1);
		when(ledgerEntryRepository.findAllAcrossTenantsForPlatformReport(any())).thenReturn(page);
		when(tenantLookupApi.resolveTenantSummaries(Set.of(TENANT_ID))).thenReturn(List.of());

		Page<PlatformLedgerEntryResponse> result = service.getPlatformDashboard(pageable);

		assertThat(result.getContent()).hasSize(1);
		PlatformLedgerEntryResponse row = result.getContent().get(0);
		assertThat(row.tenantId()).isEqualTo(TENANT_ID);
		assertThat(row.tenantName()).isNull();
	}

	/**
	 * Proves the {@code requirePlatformAdmin()} defense-in-depth check
	 * (delegating to {@link AuthenticatedPrincipalHolder#requireRole}) is
	 * genuinely enforced at this service layer, independent of the
	 * controller's {@code @PreAuthorize} - every HTTP-level "wrong role" test
	 * is rejected upstream of this class, so without this test the check here
	 * has no coverage proving it actually rejects a non-{@code
	 * PLATFORM_ADMIN} principal. Covers both entry points
	 * ({@code getPlatformDashboard}/{@code getTenantDrillDown}) and asserts
	 * neither the repository nor {@code TenantLookupApi} is ever reached -
	 * the rejection must happen before any data access, not merely produce a
	 * filtered/empty result.
	 */
	@Test
	void bothEntryPointsRejectANonPlatformAdminPrincipalBeforeTouchingAnyDependency() {
		AuthenticatedPrincipalHolder.clear();
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), null, "TENANT_ADMIN", UUID.randomUUID()));
		Pageable pageable = PageRequest.of(0, 20);

		assertThatThrownBy(() -> service.getPlatformDashboard(pageable)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.getTenantDrillDown(TENANT_ID, pageable))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(ledgerEntryRepository);
		verifyNoInteractions(tenantLookupApi);
	}

}
