package com.lms.ledgersettlementmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.service.CoursePaymentSummaryView.CoursePaymentSummaryBucket;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the read-only endpoints in {@code com.lms.ledgersettlementmanagement.web}
 * - kept as a thin, domain-local orchestration layer over {@link
 * LedgerEntryApi} (this module's own write API, also usable for its own
 * reads), {@link PaymentStatusApi} (cross-module order-id/order-detail
 * reads), and {@link LedgerViewEnrichmentService}/{@link
 * PaymentOperationalStateService} (Wave 6 §4's extended-field and
 * operational-state projections). Per {@code .claude/rules/payments.md}'s
 * "ledger-derived, never order/payment-status-derived for 'is this paid'"
 * rule, {@code PaymentStatusApi} calls here are purely for order-id/detail
 * scoping, never for determining paid/unpaid state themselves.
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

	private final LedgerEntryApi ledgerEntryApi;

	private final PaymentStatusApi paymentStatusApi;

	private final PermissionCheckService permissionCheckService;

	private final StudentLookupApi studentLookupApi;

	private final LedgerViewEnrichmentService ledgerViewEnrichmentService;

	private final PaymentOperationalStateService paymentOperationalStateService;

	private final CourseLookupApi courseLookupApi;

	public LedgerQueryService(LedgerEntryApi ledgerEntryApi, PaymentStatusApi paymentStatusApi,
			PermissionCheckService permissionCheckService, StudentLookupApi studentLookupApi,
			LedgerViewEnrichmentService ledgerViewEnrichmentService,
			PaymentOperationalStateService paymentOperationalStateService, CourseLookupApi courseLookupApi) {
		this.ledgerEntryApi = ledgerEntryApi;
		this.paymentStatusApi = paymentStatusApi;
		this.permissionCheckService = permissionCheckService;
		this.studentLookupApi = studentLookupApi;
		this.ledgerViewEnrichmentService = ledgerViewEnrichmentService;
		this.paymentOperationalStateService = paymentOperationalStateService;
		this.courseLookupApi = courseLookupApi;
	}

	/** Student's own confirmed-payment history, ledger-derived (PAY-3), extended per Wave 6 §4. */
	public List<LedgerHistoryEntryView> getHistoryForStudent(UUID studentId) {
		List<UUID> orderIds = paymentStatusApi.findOrderIdsForStudent(studentId);
		return ledgerViewEnrichmentService.enrich(ledgerEntryApi.findHistoryForOrders(orderIds));
	}

	/**
	 * Wave 3 staff-facing read: {@code GET
	 * /api/v1/students/{studentProfileId}/ledger}. {@code studentProfileId}
	 * is resolved via {@link StudentLookupApi#resolveUserId} FIRST - an id
	 * that does not resolve in the caller's own tenant is 404, never
	 * 200-with-empty-list.
	 */
	public List<LedgerHistoryEntryView> getHistoryForStudentProfile(UUID studentProfileId) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		UUID studentId = studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
		return getHistoryForStudent(studentId);
	}

	/**
	 * Tenant-admin Payment Dashboard - {@code PAYMENTS_SLIPS}/{@code
	 * VIEW}-gated, tenant-scoped only, extended per Wave 6 §4 with the
	 * {@code status}/{@code method} optional filters (both {@code null} =
	 * unfiltered, mirroring {@code SlipReviewController#getReviewQueue}'s
	 * established {@code status} param pattern).
	 *
	 * <p>When BOTH filters are {@code null}, this delegates to the original,
	 * DB-paginated {@link LedgerEntryApi#findDashboard(Pageable)} path
	 * (unchanged behavior/cost from before this wave, just now
	 * enrichment-mapped). When either filter is supplied, this instead reads
	 * every entry in the tenant via {@link
	 * LedgerEntryApi#findAllDashboardEntries()}, enriches+filters in
	 * application code, then paginates the FILTERED list itself - see that
	 * method's javadoc for why (status/method are cross-module-derived,
	 * never pushable into the {@code ledger_entry} query's {@code WHERE}
	 * clause).
	 */
	public Page<LedgerHistoryEntryView> getDashboard(Pageable pageable, PaymentOperationalState status,
			PaymentMethod method) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		if (status == null && method == null) {
			Page<LedgerHistoryEntryView> page = ledgerEntryApi.findDashboard(pageable);
			// Batch-enrich the WHOLE page content in one round trip per data
			// source (never one enrich() call per row - see
			// LedgerViewEnrichmentService's own javadoc), then re-zip the
			// enriched rows back into a Page preserving the original
			// pagination metadata.
			List<LedgerHistoryEntryView> enrichedContent = ledgerViewEnrichmentService.enrich(page.getContent());
			return new PageImpl<>(enrichedContent, pageable, page.getTotalElements());
		}
		List<LedgerHistoryEntryView> enriched = ledgerViewEnrichmentService.enrich(ledgerEntryApi
			.findAllDashboardEntries());
		List<LedgerHistoryEntryView> filtered = enriched.stream()
			.filter(view -> status == null || status == view.operationalState())
			.filter(view -> method == null || method == view.method())
			.toList();
		return paginate(filtered, pageable);
	}

	/**
	 * Wave 6 §4 - {@code GET /api/v1/ledger/outstanding}: every order in the
	 * caller's own tenant whose {@link PaymentOperationalState} is NOT
	 * {@code PAID}/{@code REFUNDED} (i.e. {@code UNPAID}/{@code PENDING}/
	 * {@code UNDER_REVIEW}/{@code REJECTED}), paginated in application code
	 * over the already-filtered list (see {@link #getDashboard}'s javadoc
	 * for why - the same cross-module-derived-field constraint applies
	 * here).
	 */
	public Page<OutstandingOrderView> getOutstanding(Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		List<UUID> orderIds = paymentStatusApi.findAllOrderIdsForCurrentTenant();
		if (orderIds.isEmpty()) {
			return new PageImpl<>(List.of(), pageable, 0);
		}
		Map<UUID, PaymentOperationalState> statesByOrderId = paymentOperationalStateService.resolveForOrders(
				orderIds);
		List<UUID> outstandingOrderIds = orderIds.stream()
			.filter(id -> isOutstanding(statesByOrderId.get(id)))
			.toList();
		if (outstandingOrderIds.isEmpty()) {
			return new PageImpl<>(List.of(), pageable, 0);
		}

		Map<UUID, OrderPaymentDetail> detailsByOrderId = paymentStatusApi
			.findOrderPaymentDetails(outstandingOrderIds)
			.stream()
			.collect(Collectors.toMap(OrderPaymentDetail::orderId, d -> d));
		Set<UUID> courseIds = detailsByOrderId.values().stream().map(OrderPaymentDetail::courseId).collect(
				Collectors.toSet());
		Map<UUID, String> courseTitlesByCourseId = courseLookupApi.getCourseSummaries(courseIds)
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name));

		List<OutstandingOrderView> views = new ArrayList<>();
		for (UUID orderId : outstandingOrderIds) {
			OrderPaymentDetail detail = detailsByOrderId.get(orderId);
			if (detail == null) {
				continue;
			}
			views.add(new OutstandingOrderView(orderId, detail.studentId(), detail.courseId(),
					courseTitlesByCourseId.get(detail.courseId()), detail.orderAmount(), detail.orderCurrency(),
					statesByOrderId.get(orderId)));
		}
		return paginate(views, pageable);
	}

	/**
	 * Wave 6 §4 - {@code GET /api/v1/ledger/courses/{courseId}/summary}:
	 * per-{@link PaymentOperationalState} order counts/amounts for one
	 * course in the caller's own tenant. See {@link CoursePaymentSummaryView}'s
	 * javadoc for the anti-enumeration "empty, never 404" contract on a
	 * nonexistent/cross-tenant {@code courseId}.
	 */
	public CoursePaymentSummaryView getCourseSummary(UUID courseId) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		List<UUID> orderIds = paymentStatusApi.findOrderIdsForCourse(courseId);
		if (orderIds.isEmpty()) {
			return new CoursePaymentSummaryView(courseId, null, 0, List.of());
		}

		Map<UUID, PaymentOperationalState> statesByOrderId = paymentOperationalStateService.resolveForOrders(
				orderIds);
		Map<UUID, OrderPaymentDetail> detailsByOrderId = paymentStatusApi.findOrderPaymentDetails(orderIds)
			.stream()
			.collect(Collectors.toMap(OrderPaymentDetail::orderId, d -> d));
		String courseTitle = courseLookupApi.getCourseSummaries(Set.of(courseId))
			.stream()
			.findFirst()
			.map(CourseSummary::name)
			.orElse(null);

		Map<PaymentOperationalState, Long> countsByState = new EnumMap<>(PaymentOperationalState.class);
		Map<PaymentOperationalState, BigDecimal> amountsByState = new EnumMap<>(PaymentOperationalState.class);
		for (UUID orderId : orderIds) {
			PaymentOperationalState state = statesByOrderId.get(orderId);
			OrderPaymentDetail detail = detailsByOrderId.get(orderId);
			if (state == null || detail == null) {
				continue;
			}
			countsByState.merge(state, 1L, Long::sum);
			// Semantic note (completion review finding #5): for every state
			// including REFUNDED, this sums each order's ORIGINAL
			// order.amount() snapshot - "order value by current state" - not
			// a net-of-refund/remaining-outstanding figure. A REFUNDED
			// order's bucket amount is therefore its full original order
			// value, never reduced by however much was actually refunded -
			// do not read this as "amount still owed back to the student".
			amountsByState.merge(state, detail.orderAmount(), BigDecimal::add);
		}

		List<CoursePaymentSummaryBucket> buckets = new ArrayList<>();
		for (PaymentOperationalState state : PaymentOperationalState.values()) {
			long count = countsByState.getOrDefault(state, 0L);
			if (count == 0) {
				continue;
			}
			buckets.add(new CoursePaymentSummaryBucket(state, count,
					amountsByState.getOrDefault(state, BigDecimal.ZERO)));
		}
		return new CoursePaymentSummaryView(courseId, courseTitle, orderIds.size(), buckets);
	}

	private static boolean isOutstanding(PaymentOperationalState state) {
		return state == PaymentOperationalState.UNPAID || state == PaymentOperationalState.PENDING
				|| state == PaymentOperationalState.UNDER_REVIEW || state == PaymentOperationalState.REJECTED;
	}

	private static <T> Page<T> paginate(List<T> items, Pageable pageable) {
		int start = (int) pageable.getOffset();
		if (start >= items.size()) {
			return new PageImpl<>(List.of(), pageable, items.size());
		}
		int end = Math.min(start + pageable.getPageSize(), items.size());
		return new PageImpl<>(items.subList(start, end), pageable, items.size());
	}

}
