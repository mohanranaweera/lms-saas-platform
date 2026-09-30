package com.lms.tenantmanagement.service;

import com.lms.identityaccessservice.api.TenantAdminInvitationApi.FirstAdminView;
import com.lms.identityaccessservice.api.TenantAdminInvitationApi;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.api.TenantStatusChangedEvent;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code PlatformAdminTenantController} (PADASH-1, TEN-2 narrowed to
 * approve/reject of a {@code PENDING_APPROVAL} tenant only - plan §21 item 1,
 * Option A; suspend/cancel of an already-active tenant stays out of scope,
 * plan §6).
 *
 * <p>Re-confirms the Platform-Admin-only gate at this service layer,
 * independent of and in addition to the controller's {@code @PreAuthorize}
 * (plan §9.4/§15 item 1/§15 item 2) - read via {@code
 * AuthenticatedPrincipalHolder} rather than importing {@code
 * identityaccessservice.service.TokenService} (a foreign {@code service}
 * package), mirroring {@code
 * com.lms.auditlogmanagement.support.AuditViewerAccessGuard}'s exact
 * cross-module-import-avoidance technique.
 */
@Service
@Transactional
public class TenantApprovalService {

	private final TenantRepository tenantRepository;

	private final TenantStatusService tenantStatusService;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantAdminInvitationApi tenantAdminInvitationApi;

	public TenantApprovalService(TenantRepository tenantRepository, TenantStatusService tenantStatusService,
			ApplicationEventPublisher eventPublisher, TenantAdminInvitationApi tenantAdminInvitationApi) {
		this.tenantRepository = tenantRepository;
		this.tenantStatusService = tenantStatusService;
		this.eventPublisher = eventPublisher;
		this.tenantAdminInvitationApi = tenantAdminInvitationApi;
	}

	/** AUTH-PWD-01: the tenant's first Tenant Admin (status only - no email in clear). */
	@Transactional(readOnly = true)
	public FirstAdminView firstAdmin(UUID tenantId) {
		requirePlatformAdmin();
		return tenantAdminInvitationApi.firstAdmin(tenantId);
	}

	/**
	 * AUTH-PWD-01: invite the first Tenant Admin (none yet), resend the
	 * invitation, or correct its email (still invited). Only for a
	 * {@code trial}/{@code active} tenant.
	 */
	public FirstAdminView inviteAdmin(UUID tenantId, String email) {
		requirePlatformAdmin();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant not found"));
		if (tenant.getStatus() != TenantStatus.TRIAL && tenant.getStatus() != TenantStatus.ACTIVE) {
			throw new ConflictException("Only a trial or active tenant can have its admin invited");
		}
		return tenantAdminInvitationApi.inviteOrResend(tenantId, email, tenant.getContactEmail(), actorId);
	}

	@Transactional(readOnly = true)
	public Page<Tenant> list(TenantStatus statusFilterOrNull, Pageable pageable) {
		requirePlatformAdmin();
		Pageable safePageable = clampPageSize(pageable);
		if (statusFilterOrNull == null) {
			return tenantRepository.findAll(safePageable);
		}
		return tenantRepository.findByStatus(statusFilterOrNull, safePageable);
	}

	@Transactional(readOnly = true)
	public Tenant getDetail(UUID tenantId) {
		requirePlatformAdmin();
		return tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant not found"));
	}

	public Tenant approve(UUID tenantId) {
		return transition(tenantId, TenantTransition.APPROVE);
	}

	public Tenant reject(UUID tenantId) {
		return transition(tenantId, TenantTransition.REJECT);
	}

	private Tenant transition(UUID tenantId, TenantTransition transition) {
		requirePlatformAdmin();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		Tenant tenant = tenantRepository.findByIdForUpdate(tenantId)
			.orElseThrow(() -> new NotFoundException("Tenant not found"));

		TenantStatus previousStatus = tenant.getStatus();
		if (previousStatus != TenantStatus.PENDING_APPROVAL) {
			throw new ConflictException("Tenant is not pending approval");
		}

		TenantStatus nextStatus = tenantStatusService.nextStatus(previousStatus, transition);
		tenant.applyStatusTransition(nextStatus);
		tenant = tenantRepository.save(tenant);

		// AUTH-PWD-01 / ADR-020: an approved tenant gets its first Tenant Admin
		// (invited, from the registration contact) in this same transaction -
		// if that fails, the approval rolls back.
		if (transition == TenantTransition.APPROVE) {
			tenantAdminInvitationApi.inviteFirstAdmin(tenant.getId(), tenant.getContactEmail(), actorId);
		}

		eventPublisher.publishEvent(
				new TenantStatusChangedEvent(tenant.getId(), actorId, previousStatus, nextStatus, Instant.now()));

		return tenant;
	}

	private void requirePlatformAdmin() {
		AuthenticatedPrincipalHolder.requireRole("PLATFORM_ADMIN");
	}

	/** Defensive server-side cap on list page size, mirroring {@code AuditLogQueryService#MAX_PAGE_SIZE}. */
	private static final int MAX_PAGE_SIZE = 100;

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

}
