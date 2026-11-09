package com.lms.tutemanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderPostageDelivery;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentRefundReportApi;
import com.lms.paymentmanagement.api.PaymentReportApi;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.tutemanagement.domain.FulfillmentSource;
import com.lms.tutemanagement.domain.Tute;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.domain.TutePaymentType;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import com.lms.tutemanagement.repository.TuteFulfillmentWrites.NewFulfillment;
import com.lms.tutemanagement.repository.TuteRepository;
import com.lms.tutemanagement.service.TutePaymentMatcher.TuteRule;
import com.lms.tutemanagement.service.TuteViews.SyncResultView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TUTE-01 section 5 automatic assignment: applies {@link TutePaymentMatcher} to CONFIRMED
 * payments and handles reversals.
 *
 * <ul>
 * <li>Tenant is always the resolved {@link TenantContext} - set from the event's own tenant by
 * {@link TutePaymentEventListener}, or the staff caller's tenant for "Sync payments".</li>
 * <li>Payment state is read-only input via payment-management's api; nothing is ever written to
 * payment, order, ledger, enrollment or entitlement tables. The payment is independently
 * re-verified as CONFIRMED for its order, and its {@code confirmed_at} is read from the payment
 * row (never trusted from the event).</li>
 * <li>Only ACTIVE tutes of the paid courses, and only payments confirmed at/after the tute's
 * creation. A payment that already has a refund / chargeback never assigns. Stock is reserved
 * under the tute row lock; out of stock = no row + structured warning (staff raise the quantity
 * and re-sync - idempotent). A payment never re-assigns a copy staff unrolled.</li>
 * <li>Reversal: the payment's non-cancelled copies get the {@code payment_reversed_at} hold flag
 * (PENDING held out of the queue, DISPATCHED flagged only - never undone).</li>
 * </ul>
 */
@Service
public class TutePaymentAssignmentService {

	private static final Logger log = LoggerFactory.getLogger(TutePaymentAssignmentService.class);

	private final TuteRepository tuteRepository;

	private final TuteFulfillmentRepository fulfillmentRepository;

	private final TuteStock stock;

	private final TuteSupport support;

	private final PaymentStatusApi paymentStatusApi;

	private final PaymentReportApi paymentReportApi;

	private final PaymentRefundReportApi refundApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	public TutePaymentAssignmentService(TuteRepository tuteRepository, TuteFulfillmentRepository fulfillmentRepository,
			TuteStock stock, TuteSupport support, PaymentStatusApi paymentStatusApi, PaymentReportApi paymentReportApi,
			PaymentRefundReportApi refundApi, TenantContext tenantContext, Clock clock) {
		this.tuteRepository = tuteRepository;
		this.fulfillmentRepository = fulfillmentRepository;
		this.stock = stock;
		this.support = support;
		this.paymentStatusApi = paymentStatusApi;
		this.paymentReportApi = paymentReportApi;
		this.refundApi = refundApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	/** One order's server-read facts. */
	private record OrderFacts(OrderPaymentDetail detail, List<OrderLineSnapshot> lines, UUID legacyCourseId,
			Map<UUID, OrderPostageDelivery> postageByCourse) {
	}

	private record Counts(int added, int already, int outOfStock) {
	}

	// ------------------------------------------------------------------
	// Payment confirmed (event listener).
	// ------------------------------------------------------------------

	/**
	 * Assigns the payer a copy of every matching ACTIVE tute of the courses the order pays for.
	 * Runs in its own transaction after the payment transaction committed.
	 * @return the number of new copies.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int assignForConfirmedPayment(UUID paymentId, UUID orderId) {
		if (!paymentStatusApi.isConfirmedForOrder(paymentId, orderId)) {
			log.atWarn()
				.setMessage("tute.auto_assign.payment_not_confirmed")
				.addKeyValue("tuteTenantId", tenantContext.getTenantId())
				.addKeyValue("paymentId", paymentId)
				.log();
			return 0;
		}
		OrderPaymentDetail detail = paymentStatusApi.findOrderPaymentDetails(List.of(orderId))
			.stream()
			.findFirst()
			.orElse(null);
		if (detail == null || detail.studentId() == null || !paymentId.equals(detail.confirmedPaymentId())) {
			return 0;
		}
		OrderFacts facts = facts(List.of(detail)).get(orderId);
		Set<UUID> courseIds = new HashSet<>();
		facts.lines().stream().map(OrderLineSnapshot::courseId).filter(Objects::nonNull).forEach(courseIds::add);
		if (facts.legacyCourseId() != null) {
			courseIds.add(facts.legacyCourseId());
		}
		List<Tute> tutes = tuteRepository.findActiveByCourseIdIn(courseIds)
			.stream()
			.sorted(Comparator.comparing(Tute::getId))
			.toList();
		if (tutes.isEmpty()) {
			return 0;
		}
		if (refunded(List.of(paymentId)).contains(paymentId)) {
			log.atInfo()
				.setMessage("tute.auto_assign.payment_reversed")
				.addKeyValue("tuteTenantId", tenantContext.getTenantId())
				.addKeyValue("paymentId", paymentId)
				.log();
			return 0;
		}
		Instant confirmedAt = paymentReportApi.findConfirmedAtByPaymentIds(List.of(paymentId)).get(paymentId);
		int added = 0;
		for (Tute tute : tutes) {
			if (!TutePaymentMatcher.matches(rule(tute), facts.lines(), facts.legacyCourseId(), confirmedAt)) {
				continue;
			}
			stock.lock(tute);
			Counts counts = assign(tute, facts, null);
			added += counts.added();
			if (counts.outOfStock() > 0) {
				warnOutOfStock(tute, paymentId);
			}
		}
		log.atInfo()
			.setMessage("tute.auto_assign.payment_processed")
			.addKeyValue("tuteTenantId", tenantContext.getTenantId())
			.addKeyValue("paymentId", paymentId)
			.addKeyValue("tutesConsidered", tutes.size())
			.addKeyValue("added", added)
			.log();
		return added;
	}

	// ------------------------------------------------------------------
	// Refund / chargeback.
	// ------------------------------------------------------------------

	/**
	 * Flags every non-cancelled copy sourced from {@code paymentId} (hold). Runs in its own
	 * transaction after the refund transaction committed. Audited when a human actor refunded.
	 * @return the number of newly flagged copies.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int holdForReversedPayment(UUID paymentId, UUID actorUserId, UUID refundId) {
		List<TuteFulfillment> rows = fulfillmentRepository.findActiveBySourcePaymentId(paymentId);
		if (rows.isEmpty()) {
			return 0;
		}
		Instant now = clock.instant();
		int flagged = 0;
		for (TuteFulfillment row : rows.stream().sorted(Comparator.comparing(TuteFulfillment::getId)).toList()) {
			stock.lock(row);
			if (row.markPaymentReversed(now)) {
				fulfillmentRepository.save(row);
				flagged++;
				if (actorUserId != null) {
					Map<String, Object> metadata = new LinkedHashMap<>();
					metadata.put("fulfillmentId", row.getId().toString());
					metadata.put("studentId", row.getStudentId().toString());
					metadata.put("paymentId", paymentId.toString());
					metadata.put("status", row.getStatus().name());
					if (refundId != null) {
						metadata.put("refundId", refundId.toString());
					}
					support.audit(actorUserId, "tute.payment_reversed_hold", "tute", row.getTuteId(), null, metadata);
				}
			}
		}
		fulfillmentRepository.flush();
		return flagged;
	}

	// ------------------------------------------------------------------
	// Staff "Sync payments" (caller's transaction).
	// ------------------------------------------------------------------

	/**
	 * The same matcher over the course's already-confirmed payments (covers slip approvals and
	 * manual receipts, which publish no event) plus reversal detection for chargebacks, which
	 * publish no refund event either: every payment-sourced copy of this tute whose payment now
	 * has a refund / chargeback is flagged.
	 */
	@Transactional
	public SyncResultView syncTute(Tute tute, UUID actorId) {
		Instant now = clock.instant();
		stock.lock(tute);
		int held = 0;
		List<TuteFulfillment> unflagged = fulfillmentRepository.findUnflaggedPaymentRowsOfTute(tute.getId());
		if (!unflagged.isEmpty()) {
			Set<UUID> reversed = refunded(unflagged.stream().map(TuteFulfillment::getSourcePaymentId).toList());
			List<TuteFulfillment> toFlag = unflagged.stream()
				.filter(row -> reversed.contains(row.getSourcePaymentId()))
				.toList();
			if (!toFlag.isEmpty()) {
				for (TuteFulfillment row : toFlag.stream().sorted(Comparator.comparing(TuteFulfillment::getId)).toList()) {
					stock.lock(row);
					if (row.markPaymentReversed(now)) {
						fulfillmentRepository.save(row);
						held++;
					}
				}
				fulfillmentRepository.flush();
			}
		}
		if (tute.getPaymentType() == TutePaymentType.FREE) {
			return new SyncResultView(0, 0, 0, held);
		}
		List<OrderPaymentDetail> confirmed = paymentStatusApi
			.findOrderPaymentDetails(paymentStatusApi.findOrderIdsForCourse(tute.getCourseId()))
			.stream()
			.filter(detail -> detail.confirmedPaymentId() != null && detail.studentId() != null)
			.toList();
		if (confirmed.isEmpty()) {
			return new SyncResultView(0, 0, 0, held);
		}
		List<UUID> paymentIds = confirmed.stream().map(OrderPaymentDetail::confirmedPaymentId).toList();
		Set<UUID> reversed = refunded(paymentIds);
		Map<UUID, Instant> confirmedAt = paymentReportApi.findConfirmedAtByPaymentIds(paymentIds);
		Map<UUID, OrderFacts> facts = facts(confirmed);
		TuteRule rule = rule(tute);
		List<OrderPaymentDetail> ordered = confirmed.stream()
			.filter(detail -> !reversed.contains(detail.confirmedPaymentId()))
			.filter(detail -> confirmedAt.containsKey(detail.confirmedPaymentId()))
			.sorted(Comparator.comparing((OrderPaymentDetail detail) -> confirmedAt.get(detail.confirmedPaymentId()))
				.thenComparing(OrderPaymentDetail::orderId))
			.toList();
		Set<UUID> seenStudents = new HashSet<>();
		int added = 0;
		int already = 0;
		int outOfStock = 0;
		for (OrderPaymentDetail detail : ordered) {
			OrderFacts orderFacts = facts.get(detail.orderId());
			if (!TutePaymentMatcher.matches(rule, orderFacts.lines(), orderFacts.legacyCourseId(),
					confirmedAt.get(detail.confirmedPaymentId()))) {
				continue;
			}
			if (!seenStudents.add(detail.studentId())) {
				continue; // one copy per student; a second qualifying payment changes nothing
			}
			Counts counts = assign(tute, orderFacts, actorId);
			added += counts.added();
			already += counts.already();
			outOfStock += counts.outOfStock();
		}
		if (outOfStock > 0) {
			warnOutOfStock(tute, null);
		}
		return new SyncResultView(added, already, outOfStock, held);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	/** Reserves one copy for the order's student; call only while holding the tute lock. */
	private Counts assign(Tute tute, OrderFacts facts, UUID actorId) {
		UUID paymentId = facts.detail().confirmedPaymentId();
		if (fulfillmentRepository.existsByTuteIdAndSourcePaymentId(tute.getId(), paymentId)) {
			return new Counts(0, 1, 0);
		}
		OrderPostageDelivery postage = facts.postageByCourse().get(tute.getCourseId());
		boolean postal = postage != null && postage.postageSelected();
		NewFulfillment row = new NewFulfillment(tute.getId(), tute.getCourseId(), facts.detail().studentId(),
				TutePaymentMatcher.methodFor(postal), FulfillmentSource.PAYMENT, paymentId, facts.detail().orderId(),
				tute.getName(), tute.getPaymentType(), tute.getPrice(), tute.getCurrency(),
				postal ? truncate(postage.recipientName(), 255) : null,
				postal ? truncate(postage.addressLine(), 255) : null, postal ? truncate(postage.city(), 100) : null,
				postal ? truncate(postage.phone(), 30) : null, actorId, clock.instant());
		return switch (stock.reserve(tute, row)) {
			case ADDED -> new Counts(1, 0, 0);
			case ALREADY_ASSIGNED -> new Counts(0, 1, 0);
			case OUT_OF_STOCK -> new Counts(0, 0, 1);
		};
	}

	private TuteRule rule(Tute tute) {
		return new TuteRule(tute.getCourseId(), tute.getPaymentType(), tute.getPaidLessonId(), tute.getCreatedAt(),
				support.monthOf(tute.getCreatedAt()));
	}

	private Map<UUID, OrderFacts> facts(Collection<OrderPaymentDetail> details) {
		List<UUID> orderIds = details.stream().map(OrderPaymentDetail::orderId).toList();
		Map<UUID, List<OrderLineSnapshot>> linesByOrder = paymentStatusApi.findOrderLinesForOrders(orderIds)
			.stream()
			.collect(Collectors.groupingBy(OrderLineSnapshot::orderId, LinkedHashMap::new, Collectors.toList()));
		Map<UUID, Map<UUID, OrderPostageDelivery>> postage = new HashMap<>();
		for (OrderPostageDelivery delivery : paymentReportApi.findOrderPostageDeliveries(orderIds)) {
			postage.computeIfAbsent(delivery.orderId(), id -> new HashMap<>()).put(delivery.courseId(), delivery);
		}
		List<UUID> legacyOrderIds = orderIds.stream().filter(id -> !linesByOrder.containsKey(id)).toList();
		Map<UUID, OrderRevenueBasis> bases = legacyOrderIds.isEmpty() ? Map.of()
				: paymentStatusApi.findOrderRevenueBases(legacyOrderIds)
					.stream()
					.collect(Collectors.toMap(OrderRevenueBasis::orderId, basis -> basis, (a, b) -> a));
		Map<UUID, OrderFacts> result = new HashMap<>();
		for (OrderPaymentDetail detail : details) {
			List<OrderLineSnapshot> lines = linesByOrder.getOrDefault(detail.orderId(), List.of());
			UUID legacyCourseId = null;
			if (lines.isEmpty()) {
				OrderRevenueBasis basis = bases.get(detail.orderId());
				legacyCourseId = basis != null && !basis.lineOrder() && basis.legacyCourseId() != null
						? basis.legacyCourseId() : detail.courseId();
			}
			result.put(detail.orderId(), new OrderFacts(detail, lines, legacyCourseId,
					postage.getOrDefault(detail.orderId(), Map.of())));
		}
		return result;
	}

	/** Payments of {@code paymentIds} with any refund / chargeback so far. */
	private Set<UUID> refunded(Collection<UUID> paymentIds) {
		List<UUID> ids = paymentIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Set.of();
		}
		return refundApi.findRefundedAmountsByPaymentIds(ids)
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue() != null && entry.getValue().compareTo(BigDecimal.ZERO) > 0)
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
	}

	private void warnOutOfStock(Tute tute, UUID paymentId) {
		log.atWarn()
			.setMessage("tute.auto_assign.out_of_stock")
			.addKeyValue("tuteTenantId", tenantContext.getTenantId())
			.addKeyValue("tuteId", tute.getId())
			.addKeyValue("paymentId", paymentId)
			.log();
	}

	private static String truncate(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String stripped = value.strip();
		return stripped.length() <= max ? stripped : stripped.substring(0, max);
	}

}
