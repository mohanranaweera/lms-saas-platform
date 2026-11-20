package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.api.AdminOrderSummary;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentFact;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PaymentStatusApi} - see that interface's javadoc. Every
 * read here goes through {@link PaymentRepository#findById}/{@link
 * StudentOrderRepository}'s inherited tenant-scoped finders, exactly like
 * every other read in this module.
 */
@Service
@Transactional(readOnly = true)
public class PaymentStatusApiImpl implements PaymentStatusApi {

	private final PaymentRepository paymentRepository;

	private final StudentOrderRepository studentOrderRepository;

	private final TenantContext tenantContext;

	private final StudentOrderLineRepository studentOrderLineRepository;

	@PersistenceContext
	private EntityManager entityManager;

	public PaymentStatusApiImpl(PaymentRepository paymentRepository, StudentOrderRepository studentOrderRepository,
			TenantContext tenantContext, StudentOrderLineRepository studentOrderLineRepository) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
		this.tenantContext = tenantContext;
		this.studentOrderLineRepository = studentOrderLineRepository;
	}

	@Override
	public boolean isConfirmedForOrder(UUID paymentId, UUID orderId) {
		return paymentRepository.findById(paymentId)
			.map(payment -> payment.getStatus() == PaymentStatus.CONFIRMED && payment.getOrderId().equals(orderId))
			.orElse(false);
	}

	@Override
	public boolean isConfirmedManualReceiptForOrder(UUID paymentId, UUID orderId) {
		return paymentRepository.findById(paymentId)
			.map(payment -> payment.getStatus() == PaymentStatus.CONFIRMED && payment.getOrderId().equals(orderId)
					&& payment.getGatewayReference() != null && payment.getGatewayReference().startsWith("MANUAL-"))
			.orElse(false);
	}

	@Override
	public List<OrderLineSnapshot> findOrderLines(UUID orderId) {
		Optional<StudentOrder> order = studentOrderRepository.findById(orderId);
		if (order.isEmpty()) {
			return List.of();
		}
		UUID studentId = order.get().getStudentId();
		return studentOrderLineRepository.findAllByOrderId(orderId)
			.stream()
			.map(line -> toSnapshot(line, studentId))
			.toList();
	}

	public static OrderLineSnapshot toSnapshot(StudentOrderLine line, UUID studentId) {
		return new OrderLineSnapshot(line.getId(), line.getOrderId(), studentId, line.getLineNo(), line.getScope(),
				line.getCourseId(), line.getModuleId(), line.getLessonId(), line.getPeriodYear(),
				line.getPeriodMonth(), line.getCoverageKey(), line.getLabel(), line.getUnitAmount(),
				line.getFacilityFeeAmount(), line.getDiscountAmount(), line.getLineTotal(), line.getCurrency(), line.isReward());
	}

	@Override
	public boolean isConfirmedForCurrentTenant(UUID paymentId) {
		return paymentRepository.findById(paymentId).map(payment -> payment.getStatus() == PaymentStatus.CONFIRMED)
			.orElse(false);
	}

	@Override
	public List<UUID> findOrderIdsForStudent(UUID studentId) {
		return studentOrderRepository.findAllByStudentId(studentId).stream().map(StudentOrder::getId).toList();
	}

	@Override
	public Map<UUID, List<UUID>> findOrderIdsForStudents(Collection<UUID> studentIds) {
		if (studentIds == null || studentIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<UUID>> result = new LinkedHashMap<>();
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(studentIds));
		for (int from = 0; from < ids.size(); from += IN_CHUNK) {
			List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
			studentOrderRepository.findAll((root, query, cb) -> root.get("studentId").in(chunk))
				.forEach(order -> result.computeIfAbsent(order.getStudentId(), ignored -> new ArrayList<>())
					.add(order.getId()));
		}
		return result;
	}

	@Override
	public List<OrderPaymentDetail> findOrderPaymentDetails(List<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		List<StudentOrder> orders = studentOrderRepository.findAllById(orderIds);
		Map<UUID, List<Payment>> paymentsByOrderId = new HashMap<>();
		for (Payment payment : paymentRepository.findAllByOrderIdIn(orderIds)) {
			paymentsByOrderId.computeIfAbsent(payment.getOrderId(), key -> new ArrayList<>()).add(payment);
		}

		List<OrderPaymentDetail> results = new ArrayList<>();
		for (StudentOrder order : orders) {
			List<Payment> payments = paymentsByOrderId.getOrDefault(order.getId(), List.of());
			boolean hasPendingPayment = false;
			boolean hasRejectedPayment = false;
			UUID confirmedPaymentId = null;
			String confirmedGatewayReference = null;
			for (Payment payment : payments) {
				switch (payment.getStatus()) {
					case PENDING -> hasPendingPayment = true;
					case REJECTED -> hasRejectedPayment = true;
					case CONFIRMED -> {
						// PAY-2/PAY-3 invariant: at most one CONFIRMED
						// payment per order - never ambiguous which one to
						// report here.
						confirmedPaymentId = payment.getId();
						confirmedGatewayReference = payment.getGatewayReference();
					}
					case REFUNDED -> {
						// Never actually set by any code path - see
						// Payment's own javadoc; nothing to do here.
					}
				}
			}
			results.add(new OrderPaymentDetail(order.getId(), order.getStudentId(), order.getCourseId(),
					order.getBillingPeriodId(), order.getAmount(), order.getCurrency(), hasPendingPayment,
					hasRejectedPayment, confirmedPaymentId, confirmedGatewayReference));
		}
		return results;
	}

	@Override
	public List<UUID> findAllOrderIdsForCurrentTenant() {
		return studentOrderRepository.findAll().stream().map(StudentOrder::getId).toList();
	}

	@Override
	public Page<AdminOrderSummary> searchOrders(Long orderNumber, Instant createdFrom, Instant createdBefore,
			Pageable pageable) {
		return studentOrderRepository.searchForAdmin(orderNumber, createdFrom, createdBefore, pageable)
			.map(PaymentStatusApiImpl::toAdminSummary);
	}

	@Override
	public List<AdminOrderSummary> searchAllOrders(Long orderNumber, Instant createdFrom, Instant createdBefore) {
		return studentOrderRepository.searchAllForAdmin(orderNumber, createdFrom, createdBefore)
			.stream()
			.map(PaymentStatusApiImpl::toAdminSummary)
			.toList();
	}

	private static AdminOrderSummary toAdminSummary(StudentOrder order) {
		return new AdminOrderSummary(order.getId(), order.getOrderNumber(), order.getDisplayOrderNumber(),
				order.getCreatedAt(), order.getStudentId(), order.getCourseId(), order.getAmount(), order.getCurrency());
	}

	@Override
	public List<UUID> findOrderIdsForCourse(UUID courseId) {
		// ADR-029: legacy orders placed for the course + line orders with a line
		// for it (multi-course orders have a NULL order.course_id). Both reads
		// are tenant-scoped repository finders.
		Set<UUID> ids = new LinkedHashSet<>();
		studentOrderRepository.findAllByCourseId(courseId).forEach(order -> ids.add(order.getId()));
		studentOrderLineRepository.findAllByCourseId(courseId).forEach(line -> ids.add(line.getOrderId()));
		return List.copyOf(ids);
	}

	@Override
	public List<OrderRevenueBasis> findOrderRevenueBases(Collection<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, Map<UUID, BigDecimal>> sharesByOrder = new HashMap<>();
		for (StudentOrderLine line : studentOrderLineRepository.findAllByOrderIdIn(orderIds)) {
			sharesByOrder.computeIfAbsent(line.getOrderId(), key -> new LinkedHashMap<>())
				.merge(line.getCourseId(), courseShareOf(line), BigDecimal::add);
		}
		List<OrderRevenueBasis> result = new ArrayList<>();
		for (StudentOrder order : studentOrderRepository.findAllById(orderIds)) {
			Map<UUID, BigDecimal> shares = sharesByOrder.getOrDefault(order.getId(), Map.of());
			result.add(new OrderRevenueBasis(order.getId(), order.getAmount(), order.getCourseId(),
					Map.copyOf(shares)));
		}
		return result;
	}

	/** RPT-01 batch size for {@code IN} lists (well below PostgreSQL's bind-parameter limit). */
	private static final int IN_CHUNK = 1000;

	@Override
	public List<OrderLineSnapshot> findOrderLinesForOrders(Collection<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(orderIds));
		List<OrderLineSnapshot> result = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += IN_CHUNK) {
			List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
			Map<UUID, UUID> studentByOrder = new HashMap<>();
			studentOrderRepository.findAllById(chunk)
				.forEach(order -> studentByOrder.put(order.getId(), order.getStudentId()));
			for (StudentOrderLine line : studentOrderLineRepository.findAllByOrderIdIn(chunk)) {
				result.add(toSnapshot(line, studentByOrder.get(line.getOrderId())));
			}
		}
		result.sort(java.util.Comparator.comparing(OrderLineSnapshot::orderId)
			.thenComparingInt(OrderLineSnapshot::lineNo));
		return result;
	}

	@Override
	public List<PaymentFact> findPaymentFacts(Collection<UUID> paymentIds) {
		if (paymentIds.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(paymentIds));
		List<PaymentFact> result = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += IN_CHUNK) {
			for (Payment payment : paymentRepository.findAllById(ids.subList(from, Math.min(ids.size(), from + IN_CHUNK)))) {
				result.add(new PaymentFact(payment.getId(), payment.getOrderId(), payment.getCurrency(),
						payment.getGatewayReference()));
			}
		}
		return result;
	}

	@Override
	public Set<UUID> findOrderIdsWithLinesInMonths(Set<YearMonth> months, Collection<UUID> lessonIdsInMonths) {
		Set<UUID> orderIds = new LinkedHashSet<>();
		studentOrderLineRepository.findMonthlyLinesInMonths(months).forEach(line -> orderIds.add(line.getOrderId()));
		List<UUID> lessons = List.copyOf(new LinkedHashSet<>(lessonIdsInMonths));
		for (int from = 0; from < lessons.size(); from += IN_CHUNK) {
			studentOrderLineRepository
				.findLessonLinesForLessons(lessons.subList(from, Math.min(lessons.size(), from + IN_CHUNK)))
				.forEach(line -> orderIds.add(line.getOrderId()));
		}
		return orderIds;
	}

	/** ADR-029 A2: see {@link OrderRevenueBasis#courseShareOfLine}. */
	private static BigDecimal courseShareOf(StudentOrderLine line) {
		return OrderRevenueBasis.courseShareOfLine(line.getLineTotal(), line.getFacilityFeeAmount());
	}

	/**
	 * KLASS-T4b - one native, tenant-scoped (explicit {@code l.tenant_id =
	 * :tenantId}, the resolved context tenant) read over {@code student_order_line}
	 * (V87; no JPA mapping of that table exists yet - KLASS-T4c owns it). The
	 * tenant is the resolved {@link TenantContext}, the student is the order's
	 * own {@code student_id}; neither comes from a request parameter.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public Set<String> findPendingCoverageKeys(UUID studentId, UUID courseId) {
		List<Object> keys = entityManager.createNativeQuery("""
				SELECT DISTINCT l.coverage_key
				FROM student_order_line l
				JOIN student_order o ON o.tenant_id = l.tenant_id AND o.id = l.order_id
				WHERE l.tenant_id = :tenantId
				  AND l.course_id = :courseId
				  AND o.student_id = :studentId
				  AND (EXISTS (SELECT 1 FROM payment p
				               WHERE p.tenant_id = o.tenant_id AND p.order_id = o.id AND p.status = 'PENDING')
				       OR EXISTS (SELECT 1 FROM payment_slip s
				               WHERE s.tenant_id = o.tenant_id AND s.order_id = o.id
				                 AND s.status IN ('SUBMITTED', 'UNDER_REVIEW')))
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("courseId", courseId)
			.setParameter("studentId", studentId)
			.getResultList();
		Set<String> result = new HashSet<>();
		keys.forEach(key -> result.add((String) key));
		return result;
	}

	/**
	 * KLASS-T8b (D24): the payment evidence an order already has. Tenant from
	 * the resolved {@link TenantContext}; {@code orderId} must be an order the
	 * caller already loaded through a tenant-scoped, owner-checked read.
	 *
	 * @param confirmed a {@code CONFIRMED} payment exists for the order.
	 * @param pending a {@code PENDING} payment exists for the order.
	 * @param approvedSlip an {@code APPROVED} slip exists for the order.
	 * @param openSlip a {@code SUBMITTED}/{@code UNDER_REVIEW} slip exists.
	 */
	public record OrderEvidence(boolean confirmed, boolean pending, boolean approvedSlip, boolean openSlip) {

	}

	public OrderEvidence findOrderEvidence(UUID orderId) {
		Object[] row = (Object[]) entityManager.createNativeQuery("""
				SELECT
				  EXISTS (SELECT 1 FROM payment p WHERE p.tenant_id = :tenantId AND p.order_id = :orderId
				          AND p.status = 'CONFIRMED'),
				  EXISTS (SELECT 1 FROM payment p WHERE p.tenant_id = :tenantId AND p.order_id = :orderId
				          AND p.status = 'PENDING'),
				  EXISTS (SELECT 1 FROM payment_slip s WHERE s.tenant_id = :tenantId AND s.order_id = :orderId
				          AND s.status = 'APPROVED'),
				  EXISTS (SELECT 1 FROM payment_slip s WHERE s.tenant_id = :tenantId AND s.order_id = :orderId
				          AND s.status IN ('SUBMITTED', 'UNDER_REVIEW'))
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("orderId", orderId)
			.getSingleResult();
		return new OrderEvidence(Boolean.TRUE.equals(row[0]), Boolean.TRUE.equals(row[1]),
				Boolean.TRUE.equals(row[2]), Boolean.TRUE.equals(row[3]));
	}

	/**
	 * KLASS-T4c - same as {@link #findPendingCoverageKeys} but ignoring {@code
	 * excludedOrderId}'s own pending payment / open slip (a retry on the SAME
	 * order is not a competing purchase). Used by the composer re-check and by
	 * the payment-initiation / slip-upload guards for line orders.
	 */
	@SuppressWarnings("unchecked")
	public Set<String> findPendingCoverageKeysExcludingOrder(UUID studentId, UUID courseId, UUID excludedOrderId) {
		List<Object> keys = entityManager.createNativeQuery("""
				SELECT DISTINCT l.coverage_key
				FROM student_order_line l
				JOIN student_order o ON o.tenant_id = l.tenant_id AND o.id = l.order_id
				WHERE l.tenant_id = :tenantId
				  AND l.course_id = :courseId
				  AND o.student_id = :studentId
				  AND o.id <> :excludedOrderId
				  AND (EXISTS (SELECT 1 FROM payment p
				               WHERE p.tenant_id = o.tenant_id AND p.order_id = o.id AND p.status = 'PENDING')
				       OR EXISTS (SELECT 1 FROM payment_slip s
				               WHERE s.tenant_id = o.tenant_id AND s.order_id = o.id
				                 AND s.status IN ('SUBMITTED', 'UNDER_REVIEW')))
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("courseId", courseId)
			.setParameter("studentId", studentId)
			.setParameter("excludedOrderId", excludedOrderId)
			.getResultList();
		Set<String> result = new HashSet<>();
		keys.forEach(key -> result.add((String) key));
		return result;
	}

}
