package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import com.lms.enrollmentmanagement.support.ReactivationAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ENR-3: submit/list/queue/approve/reject for {@code reactivation_request}
 * (plan §9/§10). Mirrors {@code SlipReviewService}'s exact atomicity/
 * authorization discipline - per {@code .claude/rules/payments.md} §8, a
 * {@link PermissionCheckService#requirePermission} pass is a coarse category
 * grant only; {@link #approve}/{@link #reject} independently re-verify the
 * request's actual {@code status == SUBMITTED} under a locked load before
 * writing anything.
 */
@Service
public class ReactivationRequestService {

	private static final String TARGET_ENTITY = "reactivation_request";

	private final ReactivationRequestRepository reactivationRequestRepository;

	private final EnrollmentRepository enrollmentRepository;

	private final EnrollmentExpiryService enrollmentExpiryService;

	private final ReactivationAccessGuard accessGuard;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public ReactivationRequestService(ReactivationRequestRepository reactivationRequestRepository,
			EnrollmentRepository enrollmentRepository, EnrollmentExpiryService enrollmentExpiryService,
			ReactivationAccessGuard accessGuard, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.reactivationRequestRepository = reactivationRequestRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.enrollmentExpiryService = enrollmentExpiryService;
		this.accessGuard = accessGuard;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	/**
	 * Owning-student-only (plan §12) - a cross-tenant or not-owned {@code
	 * enrollmentId} is a 404 (anti-enumeration), never a 403. The target
	 * enrollment must be the caller's own CURRENT row and its LIVE-computed
	 * access state must be {@code EXPIRED}, else {@code 409}.
	 *
	 * <h2>"At most one live request" pre-check (bug fix, MVP-012 review)</h2>
	 * Before this fix, the pre-check here only looked at {@code SUBMITTED}
	 * requests ({@code findCurrentOpenByEnrollmentId}), backed by {@code
	 * uq_reactivation_request_tenant_enrollment_open} (V22, also {@code
	 * SUBMITTED}-only) as the real DB-level guarantee. That left a gap:
	 * nothing stopped a second submission once an earlier request for the
	 * SAME enrollment was already {@code APPROVED} but not yet fulfilled
	 * with an order - so two separate {@code APPROVED}, unfulfilled requests
	 * could exist for one enrollment at once, which then made it possible
	 * for two different orders to each be linked to one of them, and for
	 * {@code EnrollmentActivationService}'s reactivation methods to have more
	 * than one {@code APPROVED}+linked candidate to choose between at
	 * confirmation time.
	 *
	 * <p>The fix ({@link ReactivationRequestRepository#findLiveByEnrollmentId})
	 * widens the pre-check to also reject a new submission while any {@code
	 * APPROVED}-but-unfulfilled ({@code newOrderId IS NULL}) request already
	 * exists for this enrollment. An {@code APPROVED} request that is
	 * ALREADY linked to an order is deliberately NOT blocking: at that
	 * point, {@code OrderService}'s own order-creation gate already
	 * guarantees at most one order can ever be in flight against a given
	 * still-current, still-unreactivated enrollment (an
	 * {@code ACTIVE}/successfully-reactivated enrollment is a brand-new
	 * lineage row with its own, different {@code enrollmentId}, so this
	 * specific, now-fulfilled request can never again be relevant to a
	 * FUTURE submission against the same still-{@code EXPIRED} enrollment).
	 * The net rule this method now enforces: at any given moment, at most
	 * ONE reactivation request for a given current enrollment is in a state
	 * that could still result in a future order being created against it
	 * ({@code SUBMITTED}, or {@code APPROVED} and unfulfilled) - now a
	 * database-enforced invariant, not service-layer discipline alone:
	 * {@code uq_reactivation_request_tenant_enrollment_live} (V24) replaced
	 * V22's narrower {@code SUBMITTED}-only partial unique index with one
	 * matching this method's real predicate exactly. A genuine race between
	 * two concurrent submissions is still caught by that index +
	 * the {@link DataIntegrityViolationException} guard below, mirroring
	 * {@code PaymentSlipRepository}'s established idiom.
	 */
	@Transactional
	public ReactivationRequestView submit(UUID enrollmentId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
			.orElseThrow(() -> new NotFoundException("Enrollment not found"));
		if (!principal.userId().equals(enrollment.getStudentId())) {
			// Anti-enumeration: a same-tenant student who does not own this
			// enrollment gets the same 404 as a nonexistent id.
			throw new NotFoundException("Enrollment not found");
		}
		if (enrollment.getSupersededAt() != null) {
			throw new ConflictException(
					"This enrollment has been superseded by a newer enrollment and can no longer be reactivated");
		}
		EnrollmentAccessState state = enrollmentExpiryService.resolveAccessState(enrollment.getStudentId(),
				enrollment.getCourseId());
		if (state.state() != EnrollmentAccessStateType.EXPIRED) {
			throw new ConflictException(
					"This enrollment's access has not expired - a reactivation request is not applicable");
		}
		if (reactivationRequestRepository.findLiveByEnrollmentId(enrollmentId).isPresent()) {
			throw new ConflictException("A reactivation request is already pending for this enrollment");
		}
		try {
			ReactivationRequest request = new ReactivationRequest(tenantContext.getTenantId(), enrollmentId,
					principal.userId());
			request = reactivationRequestRepository.save(request);
			return toView(request);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("A reactivation request is already pending for this enrollment");
		}
	}

	/** Owning student's own request history only - resolved from {@link AuthenticatedPrincipalHolder}. */
	@Transactional(readOnly = true)
	public Page<ReactivationRequestView> listMine(Pageable pageable) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		return reactivationRequestRepository.findAllByRequestedBy(principal.userId(), pageable).map(ReactivationRequestService::toView);
	}

	/** Owner student OR staff {@code ACCESS_EXPIRY}/{@code VIEW} - see {@link ReactivationAccessGuard}. */
	@Transactional(readOnly = true)
	public ReactivationRequestView getDetail(UUID requestId) {
		ReactivationRequest request = reactivationRequestRepository.findById(requestId)
			.orElseThrow(() -> new NotFoundException("Reactivation request not found"));
		UUID ownerStudentId = enrollmentRepository.findById(request.getEnrollmentId())
			.map(Enrollment::getStudentId)
			.orElse(null);
		accessGuard.requireOwnerOrStaffView(ownerStudentId);
		return toView(request);
	}

	/** Paginated, tenant-scoped review queue - staff {@code VIEW}-gated only, default queue = {@code SUBMITTED}, oldest-first. */
	@Transactional(readOnly = true)
	public Page<ReactivationRequestView> getQueue(ReactivationRequestStatus status, Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW);
		return reactivationRequestRepository.findReviewQueue(status, pageable).map(ReactivationRequestService::toView);
	}

	/**
	 * Staff {@code ACCESS_EXPIRY}/{@code APPROVE}-gated (Tenant-Admin-only
	 * per the already-shipped RBAC matrix). Does NOT itself touch {@code
	 * enrollment} or trigger reactivation - only flips this row's {@code
	 * status}; the actual reactivation happens later, when {@code
	 * EnrollmentActivationService}'s reactivation methods run after the new
	 * order's payment/slip confirms (plan §4.2 step 6). Idempotent on an
	 * already-{@code APPROVED} request (a no-op replay, not an error).
	 * @param note optional approval note, recorded as the audit entry's
	 * {@code reason} if supplied.
	 */
	@Transactional
	public ReactivationRequestView approve(UUID requestId, String note) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.APPROVE);
		ReactivationRequest request = reactivationRequestRepository
			.findByIdAndTenantIdForUpdate(requestId, tenantContext.getTenantId())
			.orElseThrow(() -> new NotFoundException("Reactivation request not found"));
		if (request.getStatus() == ReactivationRequestStatus.APPROVED) {
			return toView(request);
		}
		if (request.getStatus() != ReactivationRequestStatus.SUBMITTED) {
			throw new ConflictException("Only a SUBMITTED reactivation request may be approved, was "
					+ request.getStatus());
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Instant reviewedAt = Instant.now();
		request.approve(principal.userId(), reviewedAt);
		request = reactivationRequestRepository.save(request);

		// AuditLogEntry rejects a non-null BLANK reason - note is optional
		// (@Size-only, no @NotBlank, see ReactivationApproveRequest) so an
		// explicit "" must be normalized to null here, not passed through.
		String normalizedNote = (note != null && !note.isBlank()) ? note : null;
		auditLogApi.record(new AuditLogEntry(principal.userId(), "reactivation_request.approved", TARGET_ENTITY,
				request.getId(), normalizedNote, null));

		return toView(request);
	}

	/** @param reason required, non-blank rejection reason (max 1000 chars, validated at the web layer too). */
	@Transactional
	public ReactivationRequestView reject(UUID requestId, String reason) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.APPROVE);
		if (reason == null || reason.isBlank()) {
			throw new ConflictException("A rejection reason is required");
		}

		ReactivationRequest request = reactivationRequestRepository
			.findByIdAndTenantIdForUpdate(requestId, tenantContext.getTenantId())
			.orElseThrow(() -> new NotFoundException("Reactivation request not found"));
		if (request.getStatus() != ReactivationRequestStatus.SUBMITTED) {
			throw new ConflictException("Only a SUBMITTED reactivation request may be rejected, was "
					+ request.getStatus());
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Instant reviewedAt = Instant.now();
		request.reject(principal.userId(), reviewedAt);
		request = reactivationRequestRepository.save(request);

		auditLogApi.record(new AuditLogEntry(principal.userId(), "reactivation_request.rejected", TARGET_ENTITY,
				request.getId(), reason, null));

		return toView(request);
	}

	private static ReactivationRequestView toView(ReactivationRequest request) {
		return new ReactivationRequestView(request.getId(), request.getEnrollmentId(), request.getRequestedBy(),
				request.getStatus(), request.getReviewedBy(), request.getReviewedAt(), request.getNewOrderId(),
				request.getCreatedAt(), request.getUpdatedAt());
	}

}
