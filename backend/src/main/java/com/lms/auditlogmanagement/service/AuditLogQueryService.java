package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.auditlogmanagement.repository.AuditLogSpecifications;
import com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Backs {@code AuditLogController}'s single read endpoint (AUDIT-3) - mirrors
 * {@code com.lms.ledgersettlementmanagement.service.LedgerQueryService}'s
 * exact shape/style: a thin, domain-local read orchestration layer over
 * {@link AuditLogRepository}, {@link PermissionCheckService}, and {@link
 * UserProvisioningApi} (the last used purely for the best-effort {@code
 * actorDisplayName} projection, per plan §10 - never to widen or bypass the
 * tenant scoping {@link AuditLogRepository} already applies).
 */
@Service
@Transactional(readOnly = true)
public class AuditLogQueryService {

	/**
	 * Plan §21 decision 1, option B (product-owner approved): the coarse
	 * {@link DomainArea#AUDIT_LOG}/{@link PermissionAction#VIEW} grant in
	 * {@code PermissionCheckServiceImpl}'s matrix is currently held by every
	 * staff sub-role (each transcribed as "V (own-area actions)"/"V (full)"
	 * for the auditor), which would - without a narrower gate - over-expose
	 * refund amounts, actor identities, and material-deletion history to
	 * every staff sub-role before any "own-area" scoping is ever defined.
	 * This is a deliberate, interim restriction narrower than the generic
	 * grant, not a redundant duplicate of it: {@link #search} still calls
	 * {@link PermissionCheckService#requirePermission} first (the existing,
	 * unchanged mechanism), then additionally requires the caller's actual
	 * role be one of these two - Institute Owner or Read-only Auditor - via
	 * this explicit allowlist.
	 *
	 * <p>Compared directly as a raw string (rather than importing {@code
	 * com.lms.identityaccessservice.domain.Role}) because that enum lives in
	 * {@code identityaccessservice}'s {@code domain} package, not its {@code
	 * api} package - {@code .claude/rules/architecture.md} forbids any other
	 * module depending on a foreign domain's {@code domain} classes. {@link
	 * com.lms.identityaccessservice.api.AuthenticatedPrincipal#role()} is
	 * already the same live, server-re-read role string {@code
	 * PermissionCheckServiceImpl.hasPermission} itself parses with {@code
	 * Role.valueOf} - an unrecognized/absent value here denies exactly like
	 * that method's own catch block, without this module importing the enum.
	 */
	private static final Set<String> VIEWER_ALLOWED_ROLES = Set.of("TENANT_ADMIN", "READ_ONLY_AUDITOR");

	/** Defensive server-side cap on search page size, mirroring {@code CourseService#MAX_PAGE_SIZE} exactly. */
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * Allow-list of {@code sort} properties {@code AuditLogController} may
	 * bind onto {@link Pageable} - the two properties {@link
	 * AuditLogSpecifications} and this service's query actually rely on being
	 * indexed ({@code idx_audit_log_tenant_occurred_at} and {@code
	 * V30__add_audit_log_action_index.sql}'s action index). An unbound {@code
	 * sort} query parameter would otherwise flow straight into the JPA {@code
	 * Specification}/{@code Pageable} sort with no validation, either
	 * throwing an unhandled {@code PropertyReferenceException} (500) for an
	 * unknown property or forcing an unindexed sort (e.g. on {@code
	 * metadata}/{@code reason}) for a real-but-unindexed one.
	 */
	private static final Set<String> SORTABLE_PROPERTIES = Set.of("occurredAt", "action");

	private final AuditLogRepository auditLogRepository;

	private final PermissionCheckService permissionCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final ObjectMapper objectMapper;

	public AuditLogQueryService(AuditLogRepository auditLogRepository, PermissionCheckService permissionCheckService,
			UserProvisioningApi userProvisioningApi, ObjectMapper objectMapper) {
		this.auditLogRepository = auditLogRepository;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.objectMapper = objectMapper;
	}

	public Page<AuditLogEntryResponse> search(AuditLogSearchCriteria criteria, Pageable pageable) {
		// 1. Coarse, existing gate - unchanged mechanism. Runs before any
		// input validation (defense-in-depth: an unauthorized caller must
		// be rejected with 403 before this method reveals anything about
		// input validity, e.g. via a 400 for an unsortable property).
		permissionCheckService.requirePermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW);

		// 2. Narrower, MVP-specific gate - see VIEWER_ALLOWED_ROLES' javadoc
		// for why this is required in addition to (not instead of) the check
		// above.
		String role = AuthenticatedPrincipalHolder.get().role();
		if (role == null || !VIEWER_ALLOWED_ROLES.contains(role)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}

		// 3. Input validation - only reached once the caller is confirmed
		// authorized. (AuditLogSearchCriteria's own compact-constructor
		// validation still runs earlier, in AuditLogController, before
		// search() is even called - see that class's javadoc.)
		validateSort(pageable.getSort());

		// 4. Build and run the tenant-scoped, filtered, paginated read.
		Pageable safePageable = clampPageSize(pageable);
		Specification<AuditLog> spec = AuditLogSpecifications.occurredAtFrom(criteria.from())
			.and(AuditLogSpecifications.occurredAtTo(criteria.to()))
			.and(AuditLogSpecifications.withAction(criteria.action()))
			.and(AuditLogSpecifications.withTargetEntity(criteria.targetEntity()));
		Page<AuditLog> page = auditLogRepository.findAll(spec, safePageable);

		Map<UUID, String> actorDisplayNames = resolveActorDisplayNames(page.getContent());
		return page.map(auditLog -> toResponse(auditLog, actorDisplayNames));
	}

	/**
	 * Rejects any {@code sort} property outside {@link #SORTABLE_PROPERTIES},
	 * per this method's own class-level javadoc - same {@code 400
	 * InvalidAuditLogSearchException} pattern {@link AuditLogSearchCriteria}
	 * already uses for its {@code from}/{@code to} validation, rather than
	 * letting an unknown property reach the repository as an unhandled
	 * {@code PropertyReferenceException}.
	 */
	private void validateSort(Sort sort) {
		for (Sort.Order order : sort) {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new InvalidAuditLogSearchException(
						"'sort' may only reference one of " + SORTABLE_PROPERTIES + ", got '" + order.getProperty()
								+ "'");
			}
		}
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	/**
	 * Batch-resolves every distinct {@code actorId} in the page via a single
	 * {@link UserProvisioningApi#findTenantUserSummaries} call, matching that
	 * method's own documented batch-shaped intent (avoids an in-process N+1
	 * across the module boundary, one call per row).
	 */
	private Map<UUID, String> resolveActorDisplayNames(List<AuditLog> auditLogs) {
		List<UUID> actorIds = auditLogs.stream().map(AuditLog::getActorId).distinct().toList();
		return userProvisioningApi.findTenantUserSummaries(actorIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email));
	}

	private AuditLogEntryResponse toResponse(AuditLog auditLog, Map<UUID, String> actorDisplayNames) {
		return new AuditLogEntryResponse(auditLog.getId(), auditLog.getActorId(),
				actorDisplayNames.get(auditLog.getActorId()), auditLog.getAction(), auditLog.getTargetEntity(),
				auditLog.getTargetId(), auditLog.getReason(), deserializeMetadata(auditLog.getId(), auditLog.getMetadata()),
				auditLog.getOccurredAt());
	}

	private Map<String, Object> deserializeMetadata(UUID auditLogId, String metadata) {
		if (metadata == null) {
			return null;
		}
		try {
			return objectMapper.readValue(metadata, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException e) {
			// A metadata JSON value that fails to deserialize is a stored-data
			// bug (it was written by AuditLogService's own serializeMetadata),
			// not a runtime condition to swallow - fail loudly rather than
			// silently return an empty/wrong metadata map for this row.
			throw new IllegalStateException("Failed to deserialize audit log metadata for audit log " + auditLogId,
					e);
		}
	}

}
