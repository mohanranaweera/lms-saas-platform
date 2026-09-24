package com.lms.auditlogmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.identityaccessservice.domain.Role;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Plain Mockito unit test directly against {@link AuditLogQueryService},
 * mirroring {@code EnrollmentQueryServiceTest}'s established style for this
 * layer. Focused on the {@code sort} allow-list added to close the
 * unrestricted-{@code sort}-parameter finding (see {@code
 * AuditLogQueryService#SORTABLE_PROPERTIES}/{@code #validateSort}) - the
 * happy-path search behavior itself (filtering, pagination, authorization
 * allowlist) is already covered end-to-end by {@code
 * AuditLogViewerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogQueryServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ADMIN_ID = UUID.randomUUID();

	@Mock
	private AuditLogRepository auditLogRepository;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	private AuditLogQueryService service;

	@BeforeEach
	void setUp() {
		service = new AuditLogQueryService(auditLogRepository, permissionCheckService, userProvisioningApi,
				new ObjectMapper());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(ADMIN_ID, TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void sortByOccurredAtIsAccepted() {
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
		when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), org.mockito.ArgumentMatchers.eq(pageable)))
			.thenReturn(emptyPage(pageable));

		Page<AuditLogEntryResponse> result = service.search(new AuditLogSearchCriteria(null, null, null, null),
				pageable);

		assertThat(result.getContent()).isEmpty();
	}

	@Test
	void sortByActionIsAccepted() {
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "action"));
		when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), org.mockito.ArgumentMatchers.eq(pageable)))
			.thenReturn(emptyPage(pageable));

		Page<AuditLogEntryResponse> result = service.search(new AuditLogSearchCriteria(null, null, null, null),
				pageable);

		assertThat(result.getContent()).isEmpty();
	}

	/**
	 * {@code AuditLogQueryService#search} runs the coarse permission gate
	 * before {@code validateSort} (defense-in-depth: an unauthorized caller
	 * must be rejected with 403 before this method reveals anything about
	 * input validity via a 400) - so an unallowed sort property is still
	 * rejected with {@link InvalidAuditLogSearchException} for an authorized
	 * caller, but {@code permissionCheckService} is now expected to have been
	 * consulted first; the repository must still never be reached.
	 */
	@Test
	void sortByAnUnallowedPropertyThrowsInvalidAuditLogSearchExceptionAfterThePermissionCheckButNeverReachesTheRepository() {
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "metadata"));

		assertThatThrownBy(
				() -> service.search(new AuditLogSearchCriteria(null, null, null, null), pageable))
			.isInstanceOf(InvalidAuditLogSearchException.class);

		verify(permissionCheckService).requirePermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW);
		verifyNoInteractions(auditLogRepository);
	}

	@Test
	void sortByReasonIsRejected() {
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "reason"));

		assertThatThrownBy(
				() -> service.search(new AuditLogSearchCriteria(null, null, null, null), pageable))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void sortByAnArbitraryUnknownPropertyIsRejectedRatherThanReachingTheRepositoryAsAnUnhandledException() {
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "reasonOrAnyOtherUnknownField"));

		assertThatThrownBy(
				() -> service.search(new AuditLogSearchCriteria(null, null, null, null), pageable))
			.isInstanceOf(InvalidAuditLogSearchException.class);

		verifyNoInteractions(auditLogRepository);
	}

	/**
	 * Closes the null-actor-fallback finding: {@code audit_log.actor_id} is
	 * an FK-enforced {@code tenant_user} reference, so a genuinely-orphaned
	 * actor id cannot be constructed through the real HTTP/DB path (a
	 * Testcontainers insert with a bogus actor id would simply fail the FK
	 * constraint) - this Mockito-level test instead exercises {@code
	 * AuditLogQueryService#resolveActorDisplayNames}'s own documented
	 * fallback directly, the same way {@code UserProvisioningApi
	 * #findTenantUserSummaries}'s real implementation can legitimately return
	 * fewer summaries than requested ids (e.g. a caller-tenant mismatch).
	 */
	@Test
	void anActorIdThatUserProvisioningApiDoesNotResolveFallsBackToANullActorDisplayName() {
		UUID unresolvedActorId = UUID.randomUUID();
		AuditLog auditLog = new AuditLog(TENANT_ID, unresolvedActorId, "course.price_changed", "course",
				UUID.randomUUID(), null, null, Instant.now());
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
		Page<AuditLog> page = new PageImpl<>(List.of(auditLog), pageable, 1);
		when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(),
				org.mockito.ArgumentMatchers.eq(pageable))).thenReturn(page);
		when(userProvisioningApi.findTenantUserSummaries(List.of(unresolvedActorId))).thenReturn(List.of());

		Page<AuditLogEntryResponse> result = service.search(new AuditLogSearchCriteria(null, null, null, null),
				pageable);

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().get(0).actorId()).isEqualTo(unresolvedActorId);
		assertThat(result.getContent().get(0).actorDisplayName()).isNull();
	}

	/**
	 * Closes the hardcoded-role-string-allowlist finding: {@link
	 * com.lms.auditlogmanagement.support.AuditViewerAccessGuard}'s javadoc
	 * explains why it is compared as a raw string rather than the {@code
	 * Role} enum directly (cross-module {@code domain}-package dependency
	 * rule) - this proves those literals have not silently drifted from the
	 * real, live {@code Role} enum values by reflectively reading the actual
	 * field and asserting every entry still round-trips through {@code
	 * Role.valueOf}. The allowlist now lives on the shared guard (used by
	 * both {@link AuditLogQueryService#search} and {@code
	 * AuditLogService#findForTarget}), not on this service directly.
	 */
	@Test
	void viewerAllowedRoleStringLiteralsStillMatchLiveRoleEnumValues() throws Exception {
		Field field = com.lms.auditlogmanagement.support.AuditViewerAccessGuard.class
			.getDeclaredField("VIEWER_ALLOWED_ROLES");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<String> allowedRoles = (Set<String>) field.get(null);

		assertThat(allowedRoles).isNotEmpty();
		for (String roleName : allowedRoles) {
			// Throws IllegalArgumentException (failing this test) if the
			// literal no longer names a real Role enum constant.
			Role.valueOf(roleName);
		}
	}

	/**
	 * Closes the null-metadata branch: {@code
	 * AuditLogQueryService#deserializeMetadata} returns {@code null} (not an
	 * exception, not an empty map) for a row whose {@code metadata} column is
	 * {@code NULL} - the common case for an audit action with no extra
	 * structured detail (see {@code AuditLogEventListenerTest}'s {@code
	 * onCoursePriceChanged}/{@code onMaterialDeleted} entries, which both
	 * assert {@code reason()} is null but still carry metadata; this proves
	 * the complementary "no metadata at all" row shape end to end).
	 */
	@Test
	void aRowWithNullMetadataDeserializesToNullNotAnEmptyMapOrAnException() {
		AuditLog auditLog = new AuditLog(TENANT_ID, ADMIN_ID, "course.price_changed", "course", UUID.randomUUID(),
				null, null, Instant.now());
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
		Page<AuditLog> page = new PageImpl<>(List.of(auditLog), pageable, 1);
		when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(),
				org.mockito.ArgumentMatchers.eq(pageable))).thenReturn(page);
		when(userProvisioningApi.findTenantUserSummaries(List.of(ADMIN_ID))).thenReturn(List.of());

		Page<AuditLogEntryResponse> result = service.search(new AuditLogSearchCriteria(null, null, null, null),
				pageable);

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().get(0).metadata()).isNull();
	}

	private Page<AuditLog> emptyPage(Pageable pageable) {
		return new PageImpl<>(List.of(), pageable, 0);
	}

}
