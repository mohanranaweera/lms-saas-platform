package com.lms.paymentmanagement.slip.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLIP-3: the review-queue read path plus the {@code UNDER_REVIEW ->
 * APPROVED|REJECTED} write paths (plan §9/§10/§21 item 1). Mirrors {@code
 * RefundService}'s exact discipline (see its javadoc) - per {@code
 * .claude/rules/payments.md} §8, {@link PermissionCheckService#requirePermission}'s
 * {@code DomainArea#PAYMENTS_SLIPS} check is a coarse category grant only; it
 * never by itself justifies a transition, so every mutating method here
 * independently re-verifies the slip's actual {@code status ==
 * UNDER_REVIEW} under a {@code PESSIMISTIC_WRITE} lock before writing
 * anything.
 *
 * <p>{@link #approve} runs THREE checks, in this exact order, before it may
 * write anything: (1) an unlocked, scalar/projection-only idempotent-replay
 * peek ({@link PaymentSlipRepository#findStatusByIdAndTenantId} - never an
 * entity load, see that method's javadoc for why) - since {@code
 * payment_slip_flag} rows are append-only and never cleared (spec 25), a
 * slip originally approved via override permanently carries {@code hasFlags
 * == true}, so a legitimate no-reason retry against an already-{@code
 * APPROVED} slip must succeed as a no-op regardless of surviving flags, and
 * this peek needs no lock since there is nothing to lock for a read of an
 * already-terminal row; (2) the reasonless-override guard (plan §21's
 * "sharpest requirement in this module") - only needs the slip's flag
 * history (an append-only, side-effect-free read), never a locked slip row,
 * so a genuine first-time reasonless-override attempt against a flagged,
 * still-{@code UNDER_REVIEW} slip is rejected with zero row-locking and zero
 * state change; (3) the locked load/state-machine check, which also
 * re-confirms already-{@code APPROVED} as a no-op (a safety net for the
 * narrow race window between checks (1) and (3)).
 */
@Service
public class SlipReviewService {

	private static final Logger log = LoggerFactory.getLogger(SlipReviewService.class);

	private static final String TARGET_ENTITY = "payment_slip";

	private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(5);

	private final PaymentSlipRepository paymentSlipRepository;

	private final PaymentSlipFlagRepository paymentSlipFlagRepository;

	private final StudentOrderRepository studentOrderRepository;

	private final ObjectStorageApi slipStorageApi;

	private final PaymentDomainAccessGuard accessGuard;

	private final EnrollmentActivationApi enrollmentActivationApi;

	private final AuditLogApi auditLogApi;

	private final PermissionCheckService permissionCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final TenantContext tenantContext;

	public SlipReviewService(PaymentSlipRepository paymentSlipRepository,
			PaymentSlipFlagRepository paymentSlipFlagRepository, StudentOrderRepository studentOrderRepository,
			ObjectStorageApi slipStorageApi, PaymentDomainAccessGuard accessGuard,
			EnrollmentActivationApi enrollmentActivationApi, AuditLogApi auditLogApi,
			PermissionCheckService permissionCheckService, UserProvisioningApi userProvisioningApi,
			TenantContext tenantContext) {
		this.paymentSlipRepository = paymentSlipRepository;
		this.paymentSlipFlagRepository = paymentSlipFlagRepository;
		this.studentOrderRepository = studentOrderRepository;
		this.slipStorageApi = slipStorageApi;
		this.accessGuard = accessGuard;
		this.enrollmentActivationApi = enrollmentActivationApi;
		this.auditLogApi = auditLogApi;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.tenantContext = tenantContext;
	}

	/**
	 * Paginated, tenant-scoped review queue - staff {@code VIEW}-gated only
	 * (never a student, even the slip's own owner). {@code status == null}
	 * returns the actual pending-review queue ({@code SUBMITTED}/{@code
	 * UNDER_REVIEW}); a supplied status filters to that exact value.
	 * Student/reviewer emails and order amounts are batched across the WHOLE
	 * page in one {@link UserProvisioningApi#findTenantUserSummaries} call
	 * and one {@code studentOrderRepository.findAllById} call - never one
	 * cross-module call per row.
	 */
	@Transactional(readOnly = true)
	public Page<PaymentSlipView> getReviewQueue(PaymentSlipStatus status, Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		Page<PaymentSlip> page = paymentSlipRepository.findReviewQueue(status, pageable);
		List<PaymentSlip> slips = page.getContent();
		Map<UUID, StudentOrder> ordersById = loadOrdersById(slips);
		Map<UUID, TenantUserSummary> usersById = loadUsersById(slips);
		return page.map(slip -> toView(slip, ordersById, usersById));
	}

	/** Owner student OR staff {@code VIEW} (plan §10/§15) - see {@link PaymentDomainAccessGuard}. */
	@Transactional(readOnly = true)
	public PaymentSlipView getSlipDetail(UUID slipId) {
		PaymentSlip slip = paymentSlipRepository.findById(slipId)
			.orElseThrow(() -> new NotFoundException("Payment slip not found"));
		accessGuard.requireOwnerOrStaffView(slip.getStudentId());
		return toView(slip);
	}

	/** Owner student OR staff {@code VIEW}, same guard as {@link #getSlipDetail}. */
	@Transactional(readOnly = true)
	public SignedDownloadUrl getDownloadUrl(UUID slipId) {
		PaymentSlip slip = paymentSlipRepository.findById(slipId)
			.orElseThrow(() -> new NotFoundException("Payment slip not found"));
		accessGuard.requireOwnerOrStaffView(slip.getStudentId());
		return slipStorageApi.generateSignedDownloadUrl(slip.getStorageObjectKey(), DOWNLOAD_URL_TTL);
	}

	/**
	 * @param overrideReason required, non-blank, ONLY when the slip carries
	 * one or more active flags; {@code null}/blank otherwise. A
	 * reasonless-override attempt against a still-{@code UNDER_REVIEW} slip
	 * is rejected before any lock (see class javadoc); a repeat call against
	 * an already-{@code APPROVED} slip is always an idempotent no-op,
	 * regardless of flags or {@code overrideReason}.
	 */
	@Transactional
	public PaymentSlipView approve(UUID slipId, String overrideReason) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);

		List<PaymentSlipFlag> flags = paymentSlipFlagRepository.findAllBySlipId(slipId);
		boolean hasFlags = !flags.isEmpty();
		boolean hasOverrideReason = overrideReason != null && !overrideReason.isBlank();
		UUID tenantId = tenantContext.getTenantId();

		// Idempotent-replay peek: a scalar/projection status-only read, NOT
		// an entity load (see PaymentSlipRepository#findStatusByIdAndTenantId's
		// javadoc for why that distinction is load-bearing) - MUST run before
		// the reasonless-override guard below, since payment_slip_flag rows
		// are append-only and never cleared, so a slip originally approved
		// via override permanently carries hasFlags == true. Without this
		// check, a legitimate no-reason retry against an already-APPROVED
		// slip would incorrectly hit the guard below and throw, instead of
		// succeeding as the no-op the plan requires (§5 SLIP-3/§18 item 7).
		Optional<PaymentSlipStatus> currentStatus = paymentSlipRepository.findStatusByIdAndTenantId(slipId, tenantId);
		if (currentStatus.isPresent() && currentStatus.get() == PaymentSlipStatus.APPROVED) {
			PaymentSlip slip = paymentSlipRepository.findById(slipId)
				.orElseThrow(() -> new NotFoundException("Payment slip not found"));
			return toView(slip, flags);
		}

		if (hasFlags && !hasOverrideReason) {
			throw new ConflictException(
					"This slip has unresolved duplicate/suspicious flags - an override reason is required to approve it");
		}

		PaymentSlip slip = paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, tenantId)
			.orElseThrow(() -> new NotFoundException("Payment slip not found"));
		if (slip.getStatus() == PaymentSlipStatus.APPROVED) {
			// Safety net for the narrow race window between the unlocked
			// peek above and this locked load (a concurrent approve could
			// have transitioned the slip in between) - still a no-op, never
			// an error.
			return toView(slip, flags);
		}
		if (slip.getStatus() != PaymentSlipStatus.UNDER_REVIEW) {
			throw new ConflictException("Only an UNDER_REVIEW slip may be approved, was " + slip.getStatus());
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Instant reviewedAt = Instant.now();
		slip.approve(principal.userId(), reviewedAt);
		slip = paymentSlipRepository.save(slip);

		StudentOrder order = studentOrderRepository.findById(slip.getOrderId())
			.orElseThrow(() -> new NotFoundException("Order not found for this payment slip"));
		// MVP-012/ADR-013/M2: EnrollmentActivationApi's consolidated
		// activateOrReactivateFromApprovedSlip owns the
		// resolve-access-state-and-branch decision that used to be
		// duplicated here - mirrors PaymentConfirmationService's identical
		// call site, see that class's javadoc for the full rationale.
		try {
			enrollmentActivationApi.activateOrReactivateFromApprovedSlip(slip.getId(), order.getId(),
					slip.getStudentId(), order.getCourseId());
		}
		catch (IllegalStateException ex) {
			// Bug fix (MVP-012 review): mirrors PaymentConfirmationService's
			// identical activation/reactivation-refusal handling - see that
			// class's comment at the equivalent call site for the full
			// rationale. Must NOT roll back this slip's own APPROVED
			// transition or its audit log entry.
			//
			// Deliberately does NOT add a "tenantId" key here (finding L7):
			// this method always runs inside an authenticated, tenant-resolved
			// request, so CorrelationIdFilter has already put tenantId into
			// MDC, which the structured logging format includes automatically
			// - an explicit addKeyValue("tenantId", ...) here previously
			// collided with that MDC-supplied key ("The name 'tenantId' has
			// already been written"). Contrast PaymentConfirmationService's
			// sibling warn call, which DOES need an explicit tenantId key,
			// since its webhook request path never goes through the
			// authenticated filter chain that populates MDC.
			log.atWarn()
				.setMessage("enrollment.reactivation_refused")
				.addKeyValue("actor", "slip-review:" + principal.userId())
				.addKeyValue("slipId", slip.getId())
				.addKeyValue("orderId", order.getId())
				.addKeyValue("studentId", slip.getStudentId())
				.addKeyValue("courseId", order.getCourseId())
				.addKeyValue("reason", ex.getMessage())
				.log();
		}

		if (hasFlags) {
			List<String> overriddenFlagTypes = flags.stream().map(f -> f.getFlagType().name()).distinct().toList();
			auditLogApi.record(new AuditLogEntry(principal.userId(), "payment_slip.approved_with_override",
					TARGET_ENTITY, slip.getId(), overrideReason, Map.of("overriddenFlags", overriddenFlagTypes)));
		}
		else {
			auditLogApi.record(AuditLogEntry.of(principal.userId(), "payment_slip.approved", TARGET_ENTITY,
					slip.getId()));
		}

		return toView(slip, flags, order);
	}

	/** @param reason required, non-blank rejection reason. */
	@Transactional
	public PaymentSlipView reject(UUID slipId, String reason) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);
		if (reason == null || reason.isBlank()) {
			throw new ConflictException("A rejection reason is required");
		}

		PaymentSlip slip = paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, tenantContext.getTenantId())
			.orElseThrow(() -> new NotFoundException("Payment slip not found"));
		if (slip.getStatus() != PaymentSlipStatus.UNDER_REVIEW) {
			throw new ConflictException("Only an UNDER_REVIEW slip may be rejected, was " + slip.getStatus());
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Instant reviewedAt = Instant.now();
		slip.reject(principal.userId(), reviewedAt);
		slip = paymentSlipRepository.save(slip);

		auditLogApi.record(new AuditLogEntry(principal.userId(), "payment_slip.rejected", TARGET_ENTITY, slip.getId(),
				reason, null));

		return toView(slip);
	}

	// ------------------------------------------------------------------
	// View assembly - enriches PaymentSlipView with studentEmail/
	// reviewerEmail (via UserProvisioningApi, identity-access-service's
	// api) and orderAmount/orderCurrency (via the same-domain
	// StudentOrderRepository), so reviewers have an on-screen way to
	// identify who a slip belongs to and cross-check the expected amount.
	// ------------------------------------------------------------------

	private PaymentSlipView toView(PaymentSlip slip) {
		return toView(slip, paymentSlipFlagRepository.findAllBySlipId(slip.getId()));
	}

	private PaymentSlipView toView(PaymentSlip slip, List<PaymentSlipFlag> flags) {
		StudentOrder order = studentOrderRepository.findById(slip.getOrderId()).orElse(null);
		return toView(slip, flags, order);
	}

	private PaymentSlipView toView(PaymentSlip slip, List<PaymentSlipFlag> flags, StudentOrder order) {
		Map<UUID, TenantUserSummary> usersById = loadUsersById(List.of(slip));
		List<PaymentSlipFlagView> flagViews = flags.stream().map(SlipReviewService::toFlagView).toList();
		return buildView(slip, flagViews, order, usersById);
	}

	private PaymentSlipView toView(PaymentSlip slip, Map<UUID, StudentOrder> ordersById,
			Map<UUID, TenantUserSummary> usersById) {
		List<PaymentSlipFlagView> flagViews = paymentSlipFlagRepository.findAllBySlipId(slip.getId())
			.stream()
			.map(SlipReviewService::toFlagView)
			.toList();
		return buildView(slip, flagViews, ordersById.get(slip.getOrderId()), usersById);
	}

	private static PaymentSlipView buildView(PaymentSlip slip, List<PaymentSlipFlagView> flagViews,
			StudentOrder order, Map<UUID, TenantUserSummary> usersById) {
		String studentEmail = emailOf(slip.getStudentId(), usersById);
		String reviewerEmail = (slip.getReviewerId() == null) ? null : emailOf(slip.getReviewerId(), usersById);
		return new PaymentSlipView(slip.getId(), slip.getOrderId(), slip.getStudentId(), slip.getReferenceNumber(),
				slip.getStatus(), slip.getSubmittedAt(), slip.getReviewerId(), slip.getReviewedAt(), flagViews,
				studentEmail, reviewerEmail, order != null ? order.getAmount() : null,
				order != null ? order.getCurrency() : null);
	}

	private static String emailOf(UUID userId, Map<UUID, TenantUserSummary> usersById) {
		TenantUserSummary summary = usersById.get(userId);
		return (summary != null) ? summary.email() : null;
	}

	private Map<UUID, StudentOrder> loadOrdersById(List<PaymentSlip> slips) {
		Set<UUID> orderIds = new HashSet<>();
		for (PaymentSlip slip : slips) {
			orderIds.add(slip.getOrderId());
		}
		if (orderIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, StudentOrder> result = new HashMap<>();
		for (StudentOrder order : studentOrderRepository.findAllById(orderIds)) {
			result.put(order.getId(), order);
		}
		return result;
	}

	/**
	 * Batches BOTH studentId and reviewerId across {@code slips} into ONE
	 * {@link UserProvisioningApi#findTenantUserSummaries} call - never one
	 * call per row (an N+1 across the identity-access-service module
	 * boundary) and never two separate calls (one for students, one for
	 * reviewers). A {@code null} {@code reviewerId} (not-yet-reviewed slip)
	 * is filtered out before the batch call, per that API's own contract.
	 */
	private Map<UUID, TenantUserSummary> loadUsersById(List<PaymentSlip> slips) {
		Set<UUID> userIds = new HashSet<>();
		for (PaymentSlip slip : slips) {
			userIds.add(slip.getStudentId());
			if (slip.getReviewerId() != null) {
				userIds.add(slip.getReviewerId());
			}
		}
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, TenantUserSummary> result = new HashMap<>();
		for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(userIds)) {
			result.put(summary.userId(), summary);
		}
		return result;
	}

	private static PaymentSlipFlagView toFlagView(PaymentSlipFlag flag) {
		return new PaymentSlipFlagView(flag.getId(), flag.getFlagType(), flag.getDetectedAt());
	}

}
