package com.lms.paymentmanagement.order.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.api.ReactivationLinkingApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.OrderCustomAmountAppliedEvent;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates PAY-1's order lifecycle, extended for Wave 2 (Course/Class
 * model + billing foundation) to resolve checkout amounts per {@code
 * course.pricing_model} (V37) via {@link CourseLookupApi
 * #getResolvedCheckoutAmount(UUID)} instead of the old {@code
 * ONE_TIME}-only {@code getCurrentPrice} read. {@code tenant_id}/{@code
 * student_id} are ALWAYS resolved from {@link TenantContext}/{@link
 * AuthenticatedPrincipalHolder} - never from any request-body field, per
 * plan §3/§12 (the request DTO structurally has no such field at all).
 *
 * <h2>{@code CUSTOM} pricing - a documented, deferred limitation</h2>
 * This method remains STUDENT-only ({@link #requireStudent()}, unchanged
 * from PAY-1) - there is deliberately no new "staff creates an order on
 * behalf of a named student" capability added here, since {@link
 * com.lms.paymentmanagement.order.web.dto.OrderCreateRequest} has no
 * target-student field (adding one would be a materially new, security
 * -sensitive impersonation-shaped capability). The user has explicitly
 * decided to defer building that staff-on-behalf-of-student order-creation
 * endpoint to a later wave - this is the same kind of intentionally-deferred
 * dependency as {@code SESSION} billing's reliance on the not-yet-built Live
 * Sessions module, not a bug. {@code customAmount} handling below is
 * nonetheless fully implemented and independently testable at this layer
 * (permission check + audit event), ready for that future endpoint to call:
 * a {@code STUDENT} caller can never hold {@link DomainArea#COURSES}/{@link
 * PermissionAction#CREATE_EDIT} (that role is deliberately absent from
 * {@link PermissionCheckService}'s flat matrix), so a {@code CUSTOM}-priced
 * course cannot yet be purchased through student self-checkout today.
 *
 * <h2>{@code FREE} pricing - the ONLY trigger for checkout auto-activation</h2>
 * See {@code docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md}
 * for the full decision record this section summarizes (a Phase E review
 * caught the original, broader implementation and both narrower/widened
 * scopes below were explicitly reviewed and approved).
 *
 * <p>{@code payment.amount}'s CHECK constraint was widened from {@code amount
 * > 0} to {@code amount >= 0} by {@code
 * V41__allow_zero_amount_payment_for_free_courses.sql} (approved, additive,
 * {@code V19} itself untouched), so a {@code $0} {@link Payment} row can now
 * be created and driven through the SAME {@link Payment#confirm(Instant)}
 * transition every gateway-confirmed payment uses - see {@link
 * #activateFreeCheckout(StudentOrder, UUID, UUID)}. Enrollment activation
 * therefore goes through the exact same, unchanged {@link
 * EnrollmentActivationApi#activateOrReactivateFromConfirmedPayment(UUID, UUID,
 * UUID, UUID)} path a real gateway payment uses - never a new activation
 * code path.
 *
 * <p><b>Gating is on {@link CheckoutAmount#freePricing()} being {@code true}
 * (resolved by {@code CourseLookupApiImpl} from {@code course.pricing_model
 * == FREE}), never merely on the resolved amount being {@code $0}.</b>
 * {@code course.price} ({@code ONE_TIME}) and {@code
 * course_billing_period.amount} ({@code MONTHLY}/{@code SESSION}) both permit
 * {@code >= 0} with no floor above zero, so a misconfigured (or mistyped)
 * {@code $0} price/billing period on a NON-{@code FREE} course is a real,
 * reachable case - it must never be silently free-activated (no ledger
 * entry, no distinguishing audit trail) the same way a genuine {@code FREE}
 * course is. {@link #createOrder(UUID, BigDecimal)} rejects such a course's
 * order outright with a {@link ConflictException} (409) instead, before any
 * order/payment row is persisted.
 *
 * <p>A real, {@code $0} {@code ledger_entry} row (type {@code
 * PAYMENT_CONFIRMED}, amount {@code 0}) IS now written for every FREE
 * checkout, via the same {@link LedgerEntryApi#recordPaymentConfirmed(UUID,
 * UUID, BigDecimal)} call {@code PaymentConfirmationService} uses for the
 * real gateway-confirmation path - {@code ledger_entry}'s {@code
 * ck_ledger_entry_amount_nonzero CHECK (amount <> 0)} (V19) was widened by
 * {@code V42__allow_zero_amount_ledger_entry_for_free_course_confirmations.sql}
 * to an entry-type-aware {@code CHECK ((entry_type = 'PAYMENT_CONFIRMED' AND
 * amount >= 0) OR (entry_type = 'REFUND' AND amount < 0))} - deliberately
 * NOT a plain {@code amount >= 0} widening, since {@code REFUND} entries are
 * stored with a NEGATIVE amount by this module's own sign convention (see
 * {@code LedgerEntry}'s javadoc); a plain sign-agnostic widening would have
 * broken every refund (caught by {@code mvnw verify} itself before this
 * shipped - see V42's own header comment). So Payment History/the Payment
 * Dashboard (both ledger-derived, per {@code .claude/rules/payments.md} §2)
 * correctly show FREE enrollments rather than silently omitting them.
 */
@Service
@Transactional
public class OrderService {

	private static final String STUDENT_ROLE = "STUDENT";

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final StudentOrderRepository studentOrderRepository;

	private final PaymentRepository paymentRepository;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final PaymentDomainAccessGuard accessGuard;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final EnrollmentActivationApi enrollmentActivationApi;

	private final ReactivationLinkingApi reactivationLinkingApi;

	private final PermissionCheckService permissionCheckService;

	private final ApplicationEventPublisher eventPublisher;

	private final LedgerEntryApi ledgerEntryApi;

	public OrderService(StudentOrderRepository studentOrderRepository, PaymentRepository paymentRepository,
			CourseLookupApi courseLookupApi, TenantContext tenantContext, PaymentDomainAccessGuard accessGuard,
			EnrollmentAccessApi enrollmentAccessApi, EnrollmentActivationApi enrollmentActivationApi,
			ReactivationLinkingApi reactivationLinkingApi, PermissionCheckService permissionCheckService,
			ApplicationEventPublisher eventPublisher, LedgerEntryApi ledgerEntryApi) {
		this.studentOrderRepository = studentOrderRepository;
		this.paymentRepository = paymentRepository;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.accessGuard = accessGuard;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.enrollmentActivationApi = enrollmentActivationApi;
		this.reactivationLinkingApi = reactivationLinkingApi;
		this.permissionCheckService = permissionCheckService;
		this.eventPublisher = eventPublisher;
		this.ledgerEntryApi = ledgerEntryApi;
	}

	/**
	 * Server-side only. {@code courseId} must resolve to a published course
	 * within the caller's own tenant; the checkout amount is resolved via
	 * {@link CourseLookupApi#getResolvedCheckoutAmount(UUID)} at this instant
	 * per the course's current {@code pricing_model} (Wave 2), never re-read
	 * later. See this class's own javadoc for the {@code CUSTOM}/{@code
	 * FREE}/{@code $0} caveats.
	 *
	 * <p><b>Reactivation gate (MVP-012/ADR-013 §9):</b> before creating the
	 * order, resolves the caller's enrollment access state for this course -
	 * {@code NEVER_ENROLLED} proceeds unchanged (ordinary first-time
	 * purchase); {@code ACTIVE} is rejected {@code 409} ("already
	 * enrolled"); {@code EXPIRED} requires an {@code APPROVED}, unfulfilled
	 * reactivation request to already exist (checked BEFORE order creation,
	 * never discovered via a failed link call afterwards), else {@code 409}
	 * ("reactivation approval required"). On the {@code EXPIRED}+approved
	 * path, the newly-created order is linked to that request in the SAME
	 * transaction via {@link ReactivationLinkingApi}.
	 * @param customAmount only honored for a {@code CUSTOM}-priced course,
	 * and only when the caller independently holds staff {@link
	 * DomainArea#COURSES}/{@link PermissionAction#CREATE_EDIT} - see this
	 * class's javadoc for why this is currently unreachable via student
	 * self-checkout. {@code null} for every other pricing model; a non-null
	 * value supplied for a non-{@code CUSTOM} course is rejected outright.
	 */
	public OrderView createOrder(UUID courseId, BigDecimal customAmount) {
		return createOrder(courseId, customAmount, null);
	}

	/**
	 * @param idempotencyKey optional client-supplied dedup key (V52). When
	 * present and an order already exists for {@code (tenantId, studentId,
	 * idempotencyKey)}, this is treated as a replay of that same checkout
	 * submission - the existing order's view is returned unchanged ({@link
	 * OrderView#idempotentReplay()} {@code true}), with NONE of this
	 * method's other side effects (reactivation linking, FREE-checkout
	 * auto-activation, event publishing) re-run a second time. {@code null}
	 * behaves exactly as {@link #createOrder(UUID, BigDecimal)} always has.
	 *
	 * <p>The genuinely-concurrent-duplicate-request race (two checkout
	 * submissions with the same key, both reaching the pre-check replay
	 * above at the same instant) is closed via {@link
	 * StudentOrderRepository#acquireIdempotencyLock} - a transaction-scoped
	 * Postgres advisory lock acquired BEFORE the replay check, serializing
	 * the two callers so the loser's own replay check runs only after the
	 * winner's insert has already committed (and the lock released),
	 * meaning the loser observes and returns the winner's row as a replay
	 * too - never a duplicate order, and never a raw unique-constraint
	 * exception leaking to the caller. See that method's javadoc for why
	 * this - not a bare {@code catch(DataIntegrityViolationException)}
	 * around the insert - is this codebase's correct mechanism for a FRESH
	 * insert with no pre-existing row to lock.
	 */
	public OrderView createOrder(UUID courseId, BigDecimal customAmount, UUID idempotencyKey) {
		AuthenticatedPrincipal principal = requireStudent();

		if (idempotencyKey != null) {
			// See StudentOrderRepository#acquireIdempotencyLock's javadoc for
			// why this advisory lock - not a bare catch(DataIntegrityViolationException)
			// - is this codebase's correct mechanism for serializing a race
			// on a FRESH insert with no pre-existing row to lock. Blocks
			// until any other in-flight request for this exact key commits
			// or rolls back; auto-released at this transaction's end.
			studentOrderRepository.acquireIdempotencyLock(
					tenantContext.getTenantId() + ":" + principal.userId() + ":" + idempotencyKey);
			Optional<StudentOrder> existing = studentOrderRepository.findByStudentIdAndIdempotencyKey(
					principal.userId(), idempotencyKey);
			if (existing.isPresent()) {
				return toView(existing.get(), true);
			}
		}
		// Existence (in the caller's own tenant) is checked FIRST and
		// separately from "is it published" - CourseLookupApi's reads
		// resolve for any tenant-owned course regardless of status, so a
		// cross-tenant or genuinely nonexistent courseId is 404 (never
		// distinguishable from each other, per CourseLookupApi's own
		// javadoc), while a real, in-tenant-but-unpublished course is 409 -
		// matching the plan's API contract (§10: "404 if courseId doesn't
		// resolve within the caller's tenant", separate from the "409 if the
		// course isn't published/priced" case). Checking isPublished() first
		// (the previous ordering) collapsed both cases into 409, which leaks
		// nothing sensitive but is the wrong status code for the
		// cross-tenant/nonexistent case.
		CheckoutAmount checkout = courseLookupApi.getResolvedCheckoutAmount(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!courseLookupApi.isPublished(courseId)) {
			throw new ConflictException("Course is not available for enrollment");
		}

		BigDecimal amount = resolveAmount(checkout, customAmount, courseId);
		boolean isFreePricing = checkout.freePricing();
		if (!isFreePricing && amount.signum() == 0) {
			// Fix 1 (Phase E review, ADR-015): a NON-FREE course resolving to
			// a $0 checkout amount (a misconfigured/mistyped $0 ONE_TIME
			// price, or a $0 MONTHLY/SESSION billing period - both are
			// structurally reachable, since neither CoursePriceChangeRequest
			// nor CourseBillingPeriodRequest enforces a floor above zero) is
			// rejected outright, before any order/payment row is persisted -
			// never silently free-activated (which would produce enrollment
			// with no genuine payment/ledger evidence, indistinguishable from
			// a real FREE course) and never routed to a real payment gateway
			// (which cannot process an actual $0 charge anyway).
			throw new ConflictException("This course's configured price is zero but it is not priced as FREE - "
					+ "check the course's price/billing configuration");
		}

		EnrollmentAccessState accessState = enrollmentAccessApi.resolveAccessState(principal.userId(), courseId);
		boolean isReactivation = false;
		if (accessState.state() == EnrollmentAccessStateType.ACTIVE) {
			throw new ConflictException("You are already enrolled in this course");
		}
		else if (accessState.state() == EnrollmentAccessStateType.EXPIRED) {
			if (!enrollmentAccessApi.hasApprovedUnfulfilledReactivationRequest(principal.userId(), courseId)) {
				throw new ConflictException(
						"Reactivation approval is required before you can re-order this course");
			}
			isReactivation = true;
		}

		StudentOrder order = new StudentOrder(tenantContext.getTenantId(), principal.userId(), courseId, amount,
				checkout.currency(), checkout.billingPeriodId(), idempotencyKey);
		// The acquireIdempotencyLock() call above (when idempotencyKey !=
		// null) is what actually makes this insert race-safe - see its
		// javadoc. This catch is a defense-in-depth backstop only (mirrors
		// ReactivationTransactionService's identical documented stance): a
		// genuine constraint violation here means the lock above was
		// somehow bypassed, in which case Postgres has already aborted this
		// whole transaction and a plain catch cannot "un-abort" it - this
		// re-throws rather than pretending to recover from a state it
		// structurally cannot recover from mid-transaction.
		order = studentOrderRepository.save(order);

		if (checkout.requiresManualQuote()) {
			eventPublisher.publishEvent(new OrderCustomAmountAppliedEvent(tenantContext.getTenantId(), order.getId(),
					courseId, principal.userId(), amount, checkout.currency(), Instant.now()));
		}

		if (isReactivation) {
			try {
				reactivationLinkingApi.linkApprovedRequestToNewOrder(principal.userId(), courseId, order.getId());
			}
			catch (IllegalStateException ex) {
				// Bug fix (MVP-012 review): ReactivationLinkingApiImpl's
				// locked finder means a second, concurrent createOrder call
				// against the same approved-unfulfilled reactivation request
				// can genuinely lose this race (the first caller's link
				// commits first; this caller's locked read then correctly
				// finds nothing left to link) - this IllegalStateException
				// was previously uncaught here and fell through to
				// GlobalExceptionHandler's generic 500 fallback, contradicting
				// ReactivationLinkingApi's own documented "OrderService maps
				// this to 409" contract. Mapped explicitly here, at the one
				// call site that knows this specific exception means "the
				// reactivation approval this order needed is no longer
				// available" - never a generic global IllegalStateException
				// -> 409 mapping, which would be too broad and could mask
				// unrelated bugs elsewhere. This method's class-level
				// @Transactional also means the just-inserted `order` row
				// rolls back together with this rejection - the order is
				// never left half-created.
				throw new ConflictException(
						"Reactivation approval is required before you can re-order this course");
			}
		}

		// ONLY genuine FREE pricing auto-confirms - see this class's javadoc
		// and ADR-015 for why this is gated on checkout.freePricing(), never
		// on amount.signum() alone. Placed AFTER the reactivation-linking
		// step above so that, on the EXPIRED+approved-reactivation path,
		// EnrollmentActivationApi's re-verification below finds the
		// newly-linked reactivation request already in place.
		if (isFreePricing) {
			activateFreeCheckout(order, principal.userId(), courseId);
		}

		return toView(order, false);
	}

	/**
	 * Resolves the branch-specific amount and validates {@code
	 * customAmount}'s combination with the course's pricing model. See this
	 * class's own javadoc for the {@code CUSTOM}/{@code FREE} caveats. A
	 * {@code $0}-resolved amount (whether from {@code FREE} pricing, or
	 * incidentally from a {@code $0 ONE_TIME}/{@code MONTHLY}/{@code SESSION}
	 * price) is returned as-is here - {@link #createOrder(UUID, BigDecimal)}
	 * is what branches on it afterward, via {@link
	 * #activateFreeCheckout(StudentOrder, UUID, UUID)}.
	 */
	private BigDecimal resolveAmount(CheckoutAmount checkout, BigDecimal customAmount, UUID courseId) {
		if (checkout.requiresManualQuote()) {
			if (customAmount == null) {
				throw new ConflictException(
						"This course requires a manually quoted amount before it can be purchased");
			}
			// Reuses PermissionCheckService exactly the way CourseAccessGuard's
			// staff fallback does - never a bespoke role-name string check.
			// A STUDENT caller (the only caller this method ever sees - see
			// requireStudent() above) holds no grant in this flat matrix, so
			// this is always denied for genuine student self-checkout today;
			// see this class's javadoc for why that is by design.
			if (!permissionCheckService.hasPermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT)) {
				throw new AccessDeniedException(
						"Only an authorized staff member may supply a custom checkout amount");
			}
			return customAmount;
		}
		if (customAmount != null) {
			throw new ConflictException("A custom amount may only be supplied for a custom-priced course");
		}
		return checkout.amount();
	}

	/**
	 * Creates the {@link Payment} row for a {@code $0}-resolved order and
	 * drives it through the SAME {@code PENDING -> CONFIRMED} transition
	 * ({@link Payment#confirm(Instant)}) every gateway-confirmed payment
	 * uses - never constructed directly in {@code CONFIRMED} state. All in
	 * this method's caller's already-open transaction ({@link
	 * #createOrder(UUID, BigDecimal)}), per {@code .claude/rules/backend.md}
	 * ("verified payment confirmation together with enrollment activation...
	 * must share one transaction").
	 *
	 * <p>{@code gateway_reference} is synthesized as {@code "FREE-" +
	 * paymentId} - {@link Payment#confirm(Instant)} requires one to be
	 * present ({@code ck_payment_confirmed_requires_reference}, V19), and
	 * {@code uq_payment_gateway_reference} (V19) requires it be globally
	 * unique; a payment's own UUIDv7 id is already globally unique, so this
	 * value trivially satisfies that constraint without needing an actual
	 * gateway round-trip.
	 *
	 * <p>Calls the exact same {@link EnrollmentActivationApi
	 * #activateOrReactivateFromConfirmedPayment(UUID, UUID, UUID, UUID)}
	 * entry point {@code PaymentConfirmationService} (the webhook-confirmed
	 * path) uses - never a new activation code path - which independently
	 * re-verifies via {@code PaymentStatusApi#isConfirmedForCurrentTenant}
	 * that the payment is genuinely {@code CONFIRMED} before writing {@code
	 * enrollment}. A refusal ({@link IllegalStateException}) is caught and
	 * logged exactly like {@code PaymentConfirmationService} does - the
	 * payment stays {@code CONFIRMED}, no enrollment/access change happens,
	 * logged as an ops-visible inconsistency rather than failing this
	 * request or rolling back the order/payment just created.
	 *
	 * <p>Writes exactly one {@code $0} {@code PAYMENT_CONFIRMED} {@code
	 * ledger_entry} row, via the SAME {@link
	 * LedgerEntryApi#recordPaymentConfirmed(UUID, UUID, BigDecimal)} call
	 * {@code PaymentConfirmationService} makes for the real gateway
	 * -confirmation path - never a new/bespoke ledger-write code path. See
	 * this class's javadoc and ADR-015 for why this now genuinely happens
	 * (V42 widened {@code ck_ledger_entry_amount_nonzero} to permit {@code
	 * amount >= 0}) - Payment History/the Payment Dashboard are ledger
	 * -derived, so a FREE checkout with no ledger row would otherwise be
	 * invisible on both surfaces.
	 */
	private void activateFreeCheckout(StudentOrder order, UUID studentId, UUID courseId) {
		order.markPending();

		Payment payment = new Payment(order.getTenantId(), order.getId(), order.getAmount(), order.getCurrency());
		payment = paymentRepository.save(payment);
		payment.assignGatewayReference("FREE-" + payment.getId());
		PaymentStatus previousStatus = payment.getStatus();
		Instant confirmedAt = Instant.now();
		payment.confirm(confirmedAt);
		paymentRepository.save(payment);
		ledgerEntryApi.recordPaymentConfirmed(order.getId(), payment.getId(), payment.getAmount());

		try {
			enrollmentActivationApi.activateOrReactivateFromConfirmedPayment(payment.getId(), order.getId(),
					studentId, courseId);
		}
		catch (IllegalStateException ex) {
			log.atWarn()
				.setMessage("enrollment.reactivation_refused")
				.addKeyValue("actor", studentId)
				.addKeyValue("tenantId", payment.getTenantId())
				.addKeyValue("paymentId", payment.getId())
				.addKeyValue("orderId", order.getId())
				.addKeyValue("studentId", studentId)
				.addKeyValue("courseId", courseId)
				.addKeyValue("reason", ex.getMessage())
				.log();
		}

		eventPublisher.publishEvent(new PaymentConfirmedEvent(payment.getTenantId(), payment.getId(), order.getId(),
				previousStatus, PaymentStatus.CONFIRMED, confirmedAt, studentId, payment.getAmount(),
				payment.getCurrency()));
	}

	@Transactional(readOnly = true)
	public OrderView getOrder(UUID id) {
		return toView(loadOrderForCaller(id));
	}

	@Transactional(readOnly = true)
	public OrderPaymentStatusView getPaymentStatus(UUID id) {
		StudentOrder order = loadOrderForCaller(id);
		return paymentRepository.findLatestByOrderId(order.getId())
			.map(OrderService::toPaymentStatusView)
			.orElseGet(OrderPaymentStatusView::noPaymentAttemptYet);
	}

	/**
	 * Tenant/owner-checked read used by {@code PaymentInitiationService} -
	 * only the owning student (never staff, even with a {@code VIEW} grant)
	 * may initiate a payment attempt on their own order.
	 */
	@Transactional(readOnly = true)
	public StudentOrder loadOrderOwnedByCurrentStudent(UUID id) {
		StudentOrder order = studentOrderRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Order not found"));
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role()) || !principal.userId().equals(order.getStudentId())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		return order;
	}

	private StudentOrder loadOrderForCaller(UUID id) {
		StudentOrder order = studentOrderRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Order not found"));
		accessGuard.requireOwnerOrStaffView(order.getStudentId());
		return order;
	}

	private AuthenticatedPrincipal requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			// Defense in depth - OrderController already gates this with
			// hasRole('STUDENT'), mirroring CourseService's established
			// "every public method independently re-checks" discipline.
			throw new AccessDeniedException("Only a student may perform this action");
		}
		return principal;
	}

	private static OrderPaymentStatusView toPaymentStatusView(Payment payment) {
		return new OrderPaymentStatusView(true, payment.getId(), payment.getStatus(), payment.getConfirmedAt());
	}

	private static OrderView toView(StudentOrder order) {
		return toView(order, false);
	}

	private static OrderView toView(StudentOrder order, boolean idempotentReplay) {
		return new OrderView(order.getId(), order.getStudentId(), order.getCourseId(), order.getAmount(),
				order.getCurrency(), order.getBillingPeriodId(), order.getStatus(), order.getCreatedAt(),
				order.getUpdatedAt(), idempotentReplay);
	}

}
