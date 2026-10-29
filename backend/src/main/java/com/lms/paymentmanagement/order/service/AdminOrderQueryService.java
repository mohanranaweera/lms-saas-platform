package com.lms.paymentmanagement.order.service;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.OrderPaymentStateApi;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.refund.domain.PaymentRefund;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PAY-HUB-01: the staff-only, read-only whole-order view behind the Payments
 * hub drawer ({@code GET /api/v1/orders/{orderId}/admin-detail}). Requires
 * {@code PAYMENTS_SLIPS}/{@code VIEW} (a student - even the order's owner -
 * gets 403; they have {@code GET /orders/{id}}). Every read is tenant-scoped,
 * so another tenant's order id is a 404. Never mutates anything.
 */
@Service
@Transactional(readOnly = true)
public class AdminOrderQueryService {

	private final StudentOrderRepository studentOrderRepository;

	private final StudentOrderLineRepository studentOrderLineRepository;

	private final PaymentRepository paymentRepository;

	private final PaymentRefundRepository paymentRefundRepository;

	private final PaymentSlipRepository paymentSlipRepository;

	private final PermissionCheckService permissionCheckService;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final OrderPaymentStateApi orderPaymentStateApi;

	public AdminOrderQueryService(StudentOrderRepository studentOrderRepository,
			StudentOrderLineRepository studentOrderLineRepository, PaymentRepository paymentRepository,
			PaymentRefundRepository paymentRefundRepository, PaymentSlipRepository paymentSlipRepository,
			PermissionCheckService permissionCheckService, CourseLookupApi courseLookupApi,
			StudentLookupApi studentLookupApi, OrderPaymentStateApi orderPaymentStateApi) {
		this.studentOrderRepository = studentOrderRepository;
		this.studentOrderLineRepository = studentOrderLineRepository;
		this.paymentRepository = paymentRepository;
		this.paymentRefundRepository = paymentRefundRepository;
		this.paymentSlipRepository = paymentSlipRepository;
		this.permissionCheckService = permissionCheckService;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.orderPaymentStateApi = orderPaymentStateApi;
	}

	public AdminOrderDetailView getOrderDetail(UUID orderId) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		StudentOrder order = studentOrderRepository.findById(orderId)
			.orElseThrow(() -> new NotFoundException("Order not found"));

		List<StudentOrderLine> lines = studentOrderLineRepository.findAllByOrderId(orderId)
			.stream()
			.sorted(Comparator.comparingInt(StudentOrderLine::getLineNo))
			.toList();
		Set<UUID> courseIds = new HashSet<>();
		lines.forEach(line -> courseIds.add(line.getCourseId()));
		courseIds.remove(null);
		Map<UUID, String> titles = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getCourseSummaries(courseIds)
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));

		List<PaymentSlip> slips = paymentSlipRepository.findAllByOrderIdIn(List.of(orderId))
			.stream()
			.sorted(Comparator.comparing(PaymentSlip::getSubmittedAt))
			.toList();
		String approvedSlipReference = slips.stream()
			.filter(slip -> slip.getStatus() == PaymentSlipStatus.APPROVED)
			.map(PaymentSlip::getReferenceNumber)
			.reduce((first, second) -> second)
			.orElse(null);

		List<Payment> payments = paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(orderId)
			.stream()
			.sorted(Comparator.comparing(Payment::getCreatedAt))
			.toList();
		BigDecimal confirmed = BigDecimal.ZERO;
		BigDecimal refunded = BigDecimal.ZERO;
		for (Payment payment : payments) {
			if (payment.getStatus() == PaymentStatus.CONFIRMED) {
				confirmed = confirmed.add(payment.getAmount());
				for (PaymentRefund refund : paymentRefundRepository.findAllByOriginalPaymentId(payment.getId())) {
					refunded = refunded.add(refund.getAmount());
				}
			}
		}

		return new AdminOrderDetailView(order.getId(), order.getOrderNumber(), order.getDisplayOrderNumber(),
				order.getCreatedAt(), order.getStatus(),
				orderPaymentStateApi.resolveForOrders(List.of(orderId)).get(orderId), studentOf(order.getStudentId()),
				order.getCurrency(), lines.stream().map(line -> toLine(line, titles)).toList(), order.getSubtotalAmount(),
				order.getFacilityFeeAmount(), order.getPostageAmount(), order.getDiscountAmount(), order.getAmount(),
				refunded, confirmed.subtract(refunded).max(BigDecimal.ZERO), deliveryOf(order),
				payments.stream().map(payment -> toAttempt(payment, approvedSlipReference)).toList(),
				slips.stream()
					.map(slip -> new AdminOrderDetailView.Slip(slip.getId(), slip.getReferenceNumber(), slip.getStatus(),
							slip.getSubmittedAt()))
					.toList());
	}

	private AdminOrderDetailView.Student studentOf(UUID studentUserId) {
		StudentSummary summary = studentLookupApi.getStudentSummariesByUserId(List.of(studentUserId))
			.stream()
			.findFirst()
			.orElse(null);
		String mobile = studentLookupApi.findContact(studentUserId).map(StudentContact::mobile).orElse(null);
		return summary == null ? new AdminOrderDetailView.Student(null, null, null, mobile)
				: new AdminOrderDetailView.Student(summary.studentProfileId(), summary.name(), summary.email(), mobile);
	}

	private static AdminOrderDetailView.Line toLine(StudentOrderLine line, Map<UUID, String> titles) {
		return new AdminOrderDetailView.Line(line.getLineNo(), line.getCourseId(), titles.get(line.getCourseId()),
				line.getLabel(), line.getScope(), line.getUnitAmount(), line.getFacilityFeeAmount(),
				line.getDiscountAmount(), line.getLineTotal());
	}

	private static AdminOrderDetailView.PaymentAttempt toAttempt(Payment payment, String approvedSlipReference) {
		PaymentMethod method = PaymentMethod.fromGatewayReference(payment.getGatewayReference());
		String reference = method == PaymentMethod.MANUAL_SLIP && approvedSlipReference != null ? approvedSlipReference
				: payment.getGatewayReference();
		return new AdminOrderDetailView.PaymentAttempt(payment.getId(), method, payment.getStatus().name(),
				payment.getAmount(), reference, payment.getCreatedAt());
	}

	private static AdminOrderDetailView.Delivery deliveryOf(StudentOrder order) {
		StudentOrder.DeliverySnapshot delivery = order.getDelivery();
		return delivery == null ? null
				: new AdminOrderDetailView.Delivery(delivery.recipientName(), delivery.addressLine(), delivery.city(),
						delivery.phone());
	}

}
