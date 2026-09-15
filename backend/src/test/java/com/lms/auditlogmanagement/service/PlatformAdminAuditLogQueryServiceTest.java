package com.lms.auditlogmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.auditlogmanagement.web.dto.PlatformAuditLogEntryResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.api.TenantSummary;
import java.time.Instant;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Plain Mockito unit test directly against {@link PlatformAdminAuditLogQueryService},
 * mirroring {@code AuditLogQueryServiceTest}'s established style. Closes
 * plan §18 unit-test item 2's "{@code PlatformAuditLogEntryResponse} shape
 * test" the same way {@code PlatformAdminLedgerQueryServiceTest} does for
 * its sibling DTO: an assertion on the real mapping method ({@code
 * toResponse}), not a bare record-construction test, since the record has no
 * compact-constructor validation of its own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformAdminAuditLogQueryServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ACTOR_ID = UUID.randomUUID();

	@Mock
	private AuditLogRepository auditLogRepository;

	@Mock
	private TenantLookupApi tenantLookupApi;

	private PlatformAdminAuditLogQueryService service;

	@BeforeEach
	void setUp() {
		service = new PlatformAdminAuditLogQueryService(auditLogRepository, tenantLookupApi, new ObjectMapper());
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), null, "PLATFORM_ADMIN", UUID.randomUUID()));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	/**
	 * H5: proves {@link PlatformAdminAuditLogQueryService} is a genuinely
	 * separate class from its tenant-scoped sibling {@link
	 * AuditLogQueryService} - not a subtype, not the same class reused with a
	 * flag - mirroring {@code PlatformAuditLogRepositoryPathAppendOnlyTest}'s
	 * reflection/identity-check style for the analogous
	 * repository-bypass-method proof.
	 */
	@Test
	void isAStructurallyDistinctClassFromTheTenantScopedAuditLogQueryService() {
		assertThat(PlatformAdminAuditLogQueryService.class).isNotEqualTo(AuditLogQueryService.class);
		assertThat(AuditLogQueryService.class.isAssignableFrom(PlatformAdminAuditLogQueryService.class)).isFalse();
		assertThat(PlatformAdminAuditLogQueryService.class.isAssignableFrom(AuditLogQueryService.class)).isFalse();
	}

	/**
	 * H5: proves the platform-wide log path calls the explicit, reviewed
	 * cross-tenant bypass method ({@code findAllAcrossTenantsForPlatformReport})
	 * and never the tenant-scoped {@code Specification}-based {@code findAll}
	 * that {@link AuditLogQueryService} uses for its own, tenant-admin-facing
	 * view - a Mockito {@code verify(never())}-based proof that these two
	 * read paths cannot be accidentally conflated.
	 */
	@Test
	void getPlatformLogCallsOnlyTheAcrossTenantsRepositoryMethodNeverTheTenantScopedFindAll() {
		Pageable pageable = PageRequest.of(0, 20);
		when(auditLogRepository.findAllAcrossTenantsForPlatformReport(isNull(), isNull(), isNull(), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		service.getPlatformLog(null, null, null, pageable);

		verify(auditLogRepository).findAllAcrossTenantsForPlatformReport(isNull(), isNull(), isNull(), any());
		verify(auditLogRepository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class),
				any(Pageable.class));
		verify(auditLogRepository, never()).findByTenantIdAcrossTenantsForPlatformReport(any(), any(), any(), any(),
				any());
	}

	/**
	 * H5 follow-up (post-review split of the platform-report repository
	 * method into two, per {@code AuditLogRepository}'s class javadoc): the
	 * single-tenant drill-down must call the sargable, tenant-scoped
	 * repository method - never the unfiltered platform-wide one - so it
	 * cannot silently degrade back into a platform-wide scan.
	 */
	@Test
	void getTenantDrillDownCallsOnlyTheTenantScopedRepositoryMethodNeverThePlatformWideOne() {
		Pageable pageable = PageRequest.of(0, 20);
		when(tenantLookupApi.resolveTenantSummaries(Set.of(TENANT_ID)))
			.thenReturn(List.of(new TenantSummary(TENANT_ID, "Acme Institute", TenantStatus.TRIAL)));
		when(auditLogRepository.findByTenantIdAcrossTenantsForPlatformReport(eq(TENANT_ID), isNull(), isNull(),
				isNull(), any())).thenReturn(new PageImpl<>(List.of(), pageable, 0));

		service.getTenantDrillDown(TENANT_ID, null, null, null, pageable);

		verify(auditLogRepository).findByTenantIdAcrossTenantsForPlatformReport(eq(TENANT_ID), isNull(), isNull(),
				isNull(), any());
		verify(auditLogRepository, never()).findAllAcrossTenantsForPlatformReport(any(), any(), any(), any());
	}

	@Test
	void everyRowInThePlatformLogCarriesANonNullTenantIdEvenWhenTheTenantNameResolves() {
		AuditLog auditLog = new AuditLog(TENANT_ID, ACTOR_ID, "tenant.approved", "tenant", TENANT_ID, null,
				null, Instant.now());
		Pageable pageable = PageRequest.of(0, 20);
		Page<AuditLog> page = new PageImpl<>(List.of(auditLog), pageable, 1);
		when(auditLogRepository.findAllAcrossTenantsForPlatformReport(isNull(), isNull(), isNull(), any()))
			.thenReturn(page);
		when(tenantLookupApi.resolveTenantSummaries(Set.of(TENANT_ID)))
			.thenReturn(List.of(new TenantSummary(TENANT_ID, "Acme Institute", TenantStatus.TRIAL)));

		Page<PlatformAuditLogEntryResponse> result = service.getPlatformLog(null, null, null, pageable);

		assertThat(result.getContent()).hasSize(1);
		PlatformAuditLogEntryResponse row = result.getContent().get(0);
		assertThat(row.tenantId()).isEqualTo(TENANT_ID);
		assertThat(row.tenantName()).isEqualTo("Acme Institute");
	}

	@Test
	void tenantIdIsStillPopulatedWhenTheTenantNameFailsToResolve() {
		AuditLog auditLog = new AuditLog(TENANT_ID, ACTOR_ID, "tenant.approved", "tenant", TENANT_ID, null,
				null, Instant.now());
		Pageable pageable = PageRequest.of(0, 20);
		Page<AuditLog> page = new PageImpl<>(List.of(auditLog), pageable, 1);
		when(auditLogRepository.findAllAcrossTenantsForPlatformReport(isNull(), isNull(), isNull(), any()))
			.thenReturn(page);
		when(tenantLookupApi.resolveTenantSummaries(Set.of(TENANT_ID))).thenReturn(List.of());

		Page<PlatformAuditLogEntryResponse> result = service.getPlatformLog(null, null, null, pageable);

		assertThat(result.getContent()).hasSize(1);
		PlatformAuditLogEntryResponse row = result.getContent().get(0);
		assertThat(row.tenantId()).isEqualTo(TENANT_ID);
		assertThat(row.tenantName()).isNull();
	}

	/**
	 * Proves the {@code requirePlatformAdmin()} defense-in-depth check
	 * (delegating to {@link AuthenticatedPrincipalHolder#requireRole}) is
	 * genuinely enforced at this service layer, independent of the
	 * controller's {@code @PreAuthorize} - mirrors {@code
	 * PlatformAdminLedgerQueryServiceTest}'s
	 * {@code bothEntryPointsRejectANonPlatformAdminPrincipalBeforeTouchingAnyDependency}.
	 * Every HTTP-level "wrong role" test is rejected upstream of this class,
	 * so without this test the check here has no coverage proving it
	 * actually rejects a non-{@code PLATFORM_ADMIN} principal. Covers both
	 * entry points ({@code getPlatformLog}/{@code getTenantDrillDown}) and
	 * asserts neither the repository nor {@code TenantLookupApi} is ever
	 * reached - the rejection must happen before any data access, not
	 * merely produce a filtered/empty result.
	 */
	@Test
	void bothEntryPointsRejectANonPlatformAdminPrincipalBeforeTouchingAnyDependency() {
		AuthenticatedPrincipalHolder.clear();
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), null, "TENANT_ADMIN", UUID.randomUUID()));
		Pageable pageable = PageRequest.of(0, 20);

		assertThatThrownBy(() -> service.getPlatformLog(null, null, null, pageable))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.getTenantDrillDown(TENANT_ID, null, null, null, pageable))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(auditLogRepository);
		verifyNoInteractions(tenantLookupApi);
	}

}
