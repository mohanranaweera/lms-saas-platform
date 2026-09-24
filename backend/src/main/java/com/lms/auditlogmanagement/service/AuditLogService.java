package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.api.AuditActivityEntry;
import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.auditlogmanagement.repository.AuditLogSpecifications;
import com.lms.auditlogmanagement.support.AuditViewerAccessGuard;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link AuditLogApi}. {@code @Transactional} here uses Spring's
 * default {@code REQUIRED} propagation - when called from within an
 * already-open transaction (the expected/only real call shape, e.g. {@code
 * SlipReviewService#approve}), this method joins that same transaction
 * rather than opening a new one, so the audit write commits/rolls back
 * atomically with the state change it documents.
 */
@Service
public class AuditLogService implements AuditLogApi {

	private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

	private final AuditLogRepository auditLogRepository;

	private final TenantContext tenantContext;

	private final ObjectMapper objectMapper;

	private final EntityManager entityManager;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	/** Same defensive cap as {@code AuditLogQueryService#MAX_PAGE_SIZE}. */
	private static final int MAX_PAGE_SIZE = 100;

	public AuditLogService(AuditLogRepository auditLogRepository, TenantContext tenantContext,
			ObjectMapper objectMapper, EntityManager entityManager, UserProvisioningApi userProvisioningApi,
			PermissionCheckService permissionCheckService) {
		this.auditLogRepository = auditLogRepository;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
		this.entityManager = entityManager;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * Actor-existence guard shared by {@link #record} and {@link
	 * #recordForTenant}, added post-review (V32 dropped {@code
	 * fk_audit_log_actor}, since {@code actor_id} became polymorphic - see
	 * V32's header comment). Applied identically to both methods for parity:
	 * {@code record()}'s actor is expected to already be a
	 * {@code TenantContext}-validated {@code tenant_user} (re-read from a live
	 * row by {@code JwtAuthenticationFilter} at the start of the request that
	 * ultimately triggers this write), but this explicit guard is applied here
	 * too rather than relying solely on caller discipline. This is the fast,
	 * descriptive early check; a schema-level backstop for actor integrity
	 * also exists as of {@code
	 * V33__restore_audit_log_actor_integrity_trigger.sql} (a {@code BEFORE
	 * INSERT OR UPDATE OF actor_id} trigger that rejects any write - through
	 * this guard or not - whose {@code actor_id} does not resolve to a known
	 * {@code tenant_user} or {@code platform_admin_user} row), so this method
	 * is a complementary convenience, not the sole line of defense.
	 *
	 * <p>On failure this throws {@link UnknownAuditActorException} (mapped by
	 * {@code GlobalExceptionHandler} to a distinguishable {@code 5xx}/{@code
	 * UNKNOWN_AUDIT_ACTOR} response rather than falling into the generic
	 * catch-all) rather than a raw {@link IllegalArgumentException} - this
	 * represents an internal-integrity inconsistency (a JWT-authenticated
	 * principal whose id doesn't back a real row), not a caller-supplied bad
	 * argument, so it deliberately does not reuse this class's other {@code
	 * IllegalArgumentException} null-check guards' exception type.
	 *
	 * <p><b>Accepted TOCTOU window (documentation only, no behavior change):</b>
	 * this is a {@code SELECT}-based existence check, and the row it guards is
	 * persisted afterward in the same transaction with no row lock on the
	 * referenced {@code tenant_user}/{@code platform_admin_user} row - in
	 * isolation, that would be a classic time-of-check-to-time-of-use gap if
	 * the referenced actor row were deleted between this check and the
	 * subsequent insert. Two independent reasons this is not currently
	 * exploitable: (1) neither {@code TenantUserRepository} nor {@code
	 * PlatformAdminUserRepository} exposes any delete/deleteById call site
	 * anywhere in this codebase - both actor tables are only ever
	 * suspended/deactivated, never hard-deleted, so there is no code path that
	 * could remove the row between this check and the insert; (2) even if
	 * that were to change, {@code
	 * V33__restore_audit_log_actor_integrity_trigger.sql}'s {@code
	 * trg_audit_log_actor_must_exist} trigger re-validates {@code actor_id}
	 * transactionally at the moment of the actual {@code INSERT}, inside the
	 * same transaction as the write - so even a genuine concurrent delete
	 * landing inside this window would be caught atomically by the trigger
	 * rather than silently producing an orphaned audit row. This must be
	 * revisited only if reason (1) changes, i.e. if a hard-delete path is
	 * ever added for either actor table.
	 */
	private void requireKnownActor(UUID actorId) {
		if (!userProvisioningApi.actorExists(actorId)) {
			log.error(
					"Audit log write rejected: actorId {} does not resolve to a known tenant_user/platform_admin_user row",
					actorId);
			throw new UnknownAuditActorException();
		}
	}

	@Override
	@Transactional
	public void record(AuditLogEntry entry) {
		if (entry == null) {
			throw new IllegalArgumentException("entry must not be null");
		}
		requireKnownActor(entry.actorId());
		AuditLog auditLog = new AuditLog(tenantContext.getTenantId(), entry.actorId(), entry.action(),
				entry.targetEntity(), entry.targetId(), entry.reason(), serializeMetadata(entry.metadata()),
				Instant.now());
		auditLogRepository.save(auditLog);
	}

	/**
	 * Implements {@link AuditLogApi#recordForTenant(UUID, AuditLogEntry)}.
	 * Deliberately calls {@link EntityManager#persist} directly rather than
	 * {@code auditLogRepository.save(...)}: {@code AuditLogRepository} extends
	 * {@code TenantAwareRepository}, whose {@code TenantAwareRepositoryImpl
	 * #save()} unconditionally calls {@code tenantContext.getTenantId()}
	 * inside its {@code assertOwnedByCurrentTenant} guard before it even
	 * checks the entity's own {@code tenantId} field - which throws {@code
	 * TenantContextNotResolvedException} on a Platform Admin request (no
	 * {@code TenantContext} is ever resolved for {@code
	 * /api/v1/platform-admin/**}). Calling {@link EntityManager#persist}
	 * directly still runs the {@code @UuidV7} id generator and the {@code
	 * @JdbcTypeCode(SqlTypes.JSON)} metadata mapping correctly (both are
	 * standard Hibernate mechanisms, not Spring-Data-repository-specific) - it
	 * just bypasses the tenant-context-assertion wrapper, which is correct
	 * here since {@code tenantId} is an explicit, already-validated parameter
	 * rather than ambient context.
	 *
	 * <p>Guards, in order: (1) a resolved {@code TenantContext} must NOT be
	 * present on this thread - this method exists specifically for the
	 * Platform Admin request path, where no {@code TenantContext} is ever
	 * resolved; a caller invoking this from a normal tenant-scoped request
	 * (where a {@code TenantContext} IS resolved) is a misuse of this
	 * documented precondition, rejected with {@link IllegalStateException}
	 * rather than silently writing a row that {@link #record} should have
	 * written instead; (2) {@code entry.actorId()} must resolve to a known
	 * actor (see {@link #requireKnownActor}).
	 */
	@Override
	@Transactional
	public void recordForTenant(UUID tenantId, AuditLogEntry entry) {
		if (tenantId == null) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
		if (entry == null) {
			throw new IllegalArgumentException("entry must not be null");
		}
		if (TenantContextHolder.isSet()) {
			throw new IllegalStateException(
					"recordForTenant must only be called when no TenantContext is resolved (Platform Admin request "
							+ "path) - a request with a resolved TenantContext must call record(AuditLogEntry) instead");
		}
		requireKnownActor(entry.actorId());
		AuditLog auditLog = new AuditLog(tenantId, entry.actorId(), entry.action(), entry.targetEntity(),
				entry.targetId(), entry.reason(), serializeMetadata(entry.metadata()), Instant.now());
		entityManager.persist(auditLog);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<AuditActivityEntry> findForTarget(String targetEntity, UUID targetId, Pageable pageable) {
		// 1. Coarse, existing gate - unchanged mechanism.
		permissionCheckService.requirePermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW);
		// 2. Narrower, MVP-specific gate - same allowlist AuditLogQueryService#search
		// applies to the general Audit Log Viewer, via the shared AuditViewerAccessGuard
		// (see that class's javadoc). Without this, any staff sub-role holding only the
		// coarse AUDIT_LOG/VIEW grant (FINANCE_STAFF, COURSE_COORDINATOR, STUDENT_SUPPORT,
		// CONTENT_MANAGER, EXAM_MANAGER, ATTENDANCE_OPERATOR) could read a student/
		// teacher's full audit trail through this second, unguarded read path -
		// reintroducing exactly the over-exposure the allowlist exists to prevent.
		AuditViewerAccessGuard.requireViewerRole();
		Pageable safePageable = (pageable.getPageSize() <= MAX_PAGE_SIZE) ? pageable
				: PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
		Specification<AuditLog> spec = AuditLogSpecifications.withTargetEntity(targetEntity)
			.and(AuditLogSpecifications.withTargetId(targetId));
		Page<AuditLog> page = auditLogRepository.findAll(spec, safePageable);
		List<UUID> actorIds = page.getContent().stream().map(AuditLog::getActorId).distinct().toList();
		Map<UUID, String> actorDisplayNames = userProvisioningApi.findTenantUserSummaries(actorIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email));
		return page.map(auditLog -> new AuditActivityEntry(auditLog.getId(), auditLog.getActorId(),
				actorDisplayNames.get(auditLog.getActorId()), auditLog.getAction(), auditLog.getTargetEntity(),
				auditLog.getTargetId(), auditLog.getReason(), deserializeMetadata(auditLog.getMetadata()),
				auditLog.getOccurredAt()));
	}

	private Map<String, Object> deserializeMetadata(String metadata) {
		if (metadata == null) {
			return null;
		}
		try {
			return objectMapper.readValue(metadata, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException e) {
			throw new IllegalStateException("Failed to deserialize audit log metadata", e);
		}
	}

	private String serializeMetadata(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(metadata);
		}
		catch (JacksonException e) {
			// A metadata map that cannot be serialized is a caller bug, not a
			// runtime condition to swallow - failing loudly here (rather than
			// silently dropping metadata) keeps the audit row from ever
			// looking complete when it isn't.
			throw new IllegalStateException("Failed to serialize audit log metadata", e);
		}
	}

}
