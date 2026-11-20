package com.lms.ledgersettlementmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.paymentmanagement.api.AdminOrderSummary;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderNumberFormat;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderSlipDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PAY-HUB-01 {@code GET /api/v1/ledger/orders}: one row per order in the
 * caller's own tenant for the Payments hub "All Orders" tab, newest first.
 * Staff {@code PAYMENTS_SLIPS}/{@code VIEW} only.
 *
 * <p>Order number and order-date filters run in SQL (payment-management's
 * tenant-scoped order read). Status and method are cross-module-derived
 * (ledger + payments + slips), so - exactly as {@link LedgerQueryService}
 * documents for {@code /dashboard} and {@code /outstanding} - when either is
 * supplied every date/number-matching order is enriched and filtered here,
 * then paginated in application code. Without them only the requested page
 * is read and enriched.
 *
 * <p>{@code method}/{@code reference}: from the confirmed payment when there
 * is one ({@link PaymentMethod#fromGatewayReference}; a slip payment shows
 * the approved slip reference); otherwise {@code MANUAL_SLIP} while a slip is
 * open or was rejected, {@code GATEWAY} while a gateway attempt is pending or
 * was rejected, else {@code null} (no payment attempt). {@code reference} is
 * only set for a confirmed payment.
 */
@Service
@Transactional(readOnly = true)
public class AdminOrderListService {

	static final int MAX_PAGE_SIZE = 100;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	private final PaymentOperationalStateService paymentOperationalStateService;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public AdminOrderListService(PaymentStatusApi paymentStatusApi, SlipStatusApi slipStatusApi,
			PaymentOperationalStateService paymentOperationalStateService, CourseLookupApi courseLookupApi,
			StudentLookupApi studentLookupApi, PermissionCheckService permissionCheckService,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
		this.paymentOperationalStateService = paymentOperationalStateService;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	public Page<AdminOrderRowView> listOrders(String orderNumber, LocalDate from, LocalDate to,
			PaymentOperationalState status, PaymentMethod method, Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		Long number = parseOrderNumber(orderNumber);
		if (from != null && to != null && from.isAfter(to)) {
			throw new FieldValidationException("Invalid date range",
					List.of(new FieldError("from", "must be on or before 'to'")));
		}
		ZoneId zone = tenantZone();
		Instant createdFrom = from == null ? null : from.atStartOfDay(zone).toInstant();
		Instant createdBefore = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
		Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(Math.max(pageable.getPageSize(), 1),
				MAX_PAGE_SIZE));

		if (status == null && method == null) {
			Page<AdminOrderSummary> orders = paymentStatusApi.searchOrders(number, createdFrom, createdBefore, page);
			return new PageImpl<>(enrich(orders.getContent()), page, orders.getTotalElements());
		}
		List<AdminOrderRowView> filtered = enrich(
				paymentStatusApi.searchAllOrders(number, createdFrom, createdBefore))
			.stream()
			.filter(row -> status == null || status == row.operationalState())
			.filter(row -> method == null || method == row.method())
			.toList();
		int start = (int) Math.min(page.getOffset(), filtered.size());
		int end = Math.min(start + page.getPageSize(), filtered.size());
		return new PageImpl<>(filtered.subList(start, end), page, filtered.size());
	}

	private static Long parseOrderNumber(String orderNumber) {
		if (orderNumber == null || orderNumber.isBlank()) {
			return null;
		}
		return OrderNumberFormat.parse(orderNumber)
			.stream()
			.boxed()
			.findFirst()
			.orElseThrow(() -> new FieldValidationException("Invalid order number",
					List.of(new FieldError("orderNumber", "must look like ORD-000123 or 123"))));
	}

	/** Batched enrichment of a list of orders, preserving its order - a fixed number of reads, never one per row. */
	private List<AdminOrderRowView> enrich(List<AdminOrderSummary> orders) {
		if (orders.isEmpty()) {
			return List.of();
		}
		List<UUID> orderIds = orders.stream().map(AdminOrderSummary::orderId).toList();
		Map<UUID, PaymentOperationalState> states = paymentOperationalStateService.resolveForOrders(orderIds);
		Map<UUID, OrderPaymentDetail> payments = new HashMap<>();
		paymentStatusApi.findOrderPaymentDetails(orderIds).forEach(detail -> payments.put(detail.orderId(), detail));
		Map<UUID, OrderSlipDetail> slips = new HashMap<>();
		slipStatusApi.findOrderSlipDetails(orderIds).forEach(detail -> slips.put(detail.orderId(), detail));

		Map<UUID, List<OrderLineSnapshot>> linesByOrder = paymentStatusApi.findOrderLinesForOrders(orderIds)
			.stream()
			.sorted(Comparator.comparingInt(OrderLineSnapshot::lineNo))
			.collect(Collectors.groupingBy(OrderLineSnapshot::orderId));
		Set<UUID> courseIds = new HashSet<>();
		orders.forEach(order -> courseIds.add(order.courseId()));
		linesByOrder.values().forEach(lines -> lines.forEach(line -> courseIds.add(line.courseId())));
		courseIds.remove(null);
		Map<UUID, String> titles = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getCourseSummaries(courseIds)
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));

		Map<UUID, StudentSummary> students = new HashMap<>();
		studentLookupApi
			.getStudentSummariesByUserId(orders.stream().map(AdminOrderSummary::studentId).distinct().toList())
			.forEach(summary -> students.put(summary.userId(), summary));

		List<AdminOrderRowView> rows = new ArrayList<>(orders.size());
		for (AdminOrderSummary order : orders) {
			List<OrderLineSnapshot> lines = linesByOrder.getOrDefault(order.orderId(), List.of());
			UUID firstCourseId = lines.isEmpty() ? order.courseId() : lines.get(0).courseId();
			OrderPaymentDetail payment = payments.get(order.orderId());
			OrderSlipDetail slip = slips.get(order.orderId());
			PaymentMethod method = methodOf(payment, slip);
			String reference = null;
			if (payment != null && payment.confirmedGatewayReference() != null) {
				reference = method == PaymentMethod.MANUAL_SLIP && slip != null && slip.approvedReferenceNumber() != null
						? slip.approvedReferenceNumber() : payment.confirmedGatewayReference();
			}
			StudentSummary student = students.get(order.studentId());
			rows.add(new AdminOrderRowView(order.orderId(), order.orderNumber(), order.displayOrderNumber(),
					order.createdAt(), student == null ? null : student.studentProfileId(),
					student == null ? null : student.name(), Math.max(lines.size(), 1), titles.get(firstCourseId),
					order.amount(), order.currency(), states.get(order.orderId()), method, reference));
		}
		return rows;
	}

	static PaymentMethod methodOf(OrderPaymentDetail payment, OrderSlipDetail slip) {
		if (payment != null && payment.confirmedGatewayReference() != null) {
			return PaymentMethod.fromGatewayReference(payment.confirmedGatewayReference());
		}
		if (slip != null && (slip.hasOpenSlip() || slip.hasRejectedSlip())) {
			return PaymentMethod.MANUAL_SLIP;
		}
		if (payment != null && (payment.hasPendingPayment() || payment.hasRejectedPayment())) {
			return PaymentMethod.GATEWAY;
		}
		return null;
	}

	private ZoneId tenantZone() {
		try {
			Object configured = tenantConfigApi
				.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zone && !zone.isBlank()) {
				return ZoneId.of(zone);
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// fall through to UTC
		}
		return ZoneId.of("UTC");
	}

}
