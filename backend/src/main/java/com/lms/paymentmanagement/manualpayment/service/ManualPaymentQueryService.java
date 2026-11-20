package com.lms.paymentmanagement.manualpayment.service;

import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.paymentmanagement.checkout.service.CheckoutComposerService;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOffers;
import com.lms.paymentmanagement.manualpayment.domain.ManualPaymentReceipt;
import com.lms.paymentmanagement.manualpayment.repository.ManualPaymentReceiptRepository;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.ContextCourse;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.HistoryEntry;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.HistoryReceipt;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.PaymentContext;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.StudentInfo;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-030 staff reads for the manual payment page (plan §4), all {@code
 * PAYMENTS_SLIPS/VIEW}. The student is always the URL's student_profile id
 * resolved server-side in the current tenant (404 otherwise); every read is
 * tenant-scoped by the repositories / {@code api} contracts.
 */
@Service
public class ManualPaymentQueryService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final ManualPaymentSupport support;

	private final PermissionCheckService permissionCheckService;

	private final StudentLookupApi studentLookupApi;

	private final ContentCoverageApi contentCoverageApi;

	private final CourseLookupApi courseLookupApi;

	private final CheckoutComposerService composerService;

	private final StudentOrderRepository orderRepository;

	private final StudentOrderLineRepository lineRepository;

	private final PaymentRepository paymentRepository;

	private final PaymentSlipRepository slipRepository;

	private final PaymentRefundRepository refundRepository;

	private final ManualPaymentReceiptRepository receiptRepository;

	private final UserProvisioningApi userProvisioningApi;

	public ManualPaymentQueryService(ManualPaymentSupport support, PermissionCheckService permissionCheckService,
			StudentLookupApi studentLookupApi, ContentCoverageApi contentCoverageApi, CourseLookupApi courseLookupApi,
			CheckoutComposerService composerService, StudentOrderRepository orderRepository,
			StudentOrderLineRepository lineRepository, PaymentRepository paymentRepository,
			PaymentSlipRepository slipRepository, PaymentRefundRepository refundRepository,
			ManualPaymentReceiptRepository receiptRepository, UserProvisioningApi userProvisioningApi) {
		this.support = support;
		this.permissionCheckService = permissionCheckService;
		this.studentLookupApi = studentLookupApi;
		this.contentCoverageApi = contentCoverageApi;
		this.courseLookupApi = courseLookupApi;
		this.composerService = composerService;
		this.orderRepository = orderRepository;
		this.lineRepository = lineRepository;
		this.paymentRepository = paymentRepository;
		this.slipRepository = slipRepository;
		this.refundRepository = refundRepository;
		this.receiptRepository = receiptRepository;
		this.userProvisioningApi = userProvisioningApi;
	}

	/** Student card + the non-DRAFT courses the student participates in, by title. */
	@Transactional(readOnly = true)
	public PaymentContext paymentContext(UUID studentProfileId) {
		requireView();
		UUID userId = support.requireStudentUserId(studentProfileId);
		Optional<StudentContact> contact = studentLookupApi.findContact(userId);
		List<ContextCourse> courses = new ArrayList<>();
		for (UUID courseId : contentCoverageApi.findCourseIds(userId)) {
			courseLookupApi.findCatalogInfo(courseId)
				.filter(info -> !"DRAFT".equals(info.status()))
				.ifPresent(info -> courses.add(toContextCourse(info)));
		}
		courses.sort(Comparator.comparing((ContextCourse c) -> c.title() == null ? "" : c.title(),
				String.CASE_INSENSITIVE_ORDER).thenComparing(ContextCourse::courseId));
		return new PaymentContext(
				new StudentInfo(studentProfileId, contact.map(StudentContact::name).orElse(null),
						contact.map(StudentContact::email).orElse(null),
						contact.map(StudentContact::mobile).orElse(null)),
				support.tenantZone().getId(), List.copyOf(courses));
	}

	private static ContextCourse toContextCourse(CourseCatalogInfo info) {
		String currency = info.monthlyCurrency() != null ? info.monthlyCurrency() : info.currency();
		return new ContextCourse(info.courseId(), info.name(), info.pricingModel(), info.monthlyAmount(), currency,
				info.postageFee());
	}

	/** The existing composer offers, for the resolved student. */
	@Transactional(readOnly = true)
	public CheckoutOffers offers(UUID studentProfileId, UUID courseId, String scope, Integer year) {
		requireView();
		UUID userId = support.requireStudentUserId(studentProfileId);
		return composerService.offersForStudent(userId, courseId, scope, year);
	}

	/**
	 * The student's orders with at least one line for {@code courseId} and some
	 * payment evidence (unpaid carts are not payments), newest first.
	 */
	@Transactional(readOnly = true)
	public List<HistoryEntry> history(UUID studentProfileId, UUID courseId) {
		requireView();
		UUID userId = support.requireStudentUserId(studentProfileId);
		List<StudentOrder> orders = orderRepository.findAllByStudentId(userId);
		if (orders.isEmpty() || courseId == null) {
			return List.of();
		}
		List<UUID> orderIds = orders.stream().map(StudentOrder::getId).toList();
		Map<UUID, List<StudentOrderLine>> linesByOrder = new HashMap<>();
		for (StudentOrderLine line : lineRepository.findAllByOrderIdIn(orderIds)) {
			linesByOrder.computeIfAbsent(line.getOrderId(), id -> new ArrayList<>()).add(line);
		}
		List<StudentOrder> matching = orders.stream()
			.filter(order -> linesByOrder.getOrDefault(order.getId(), List.of())
				.stream()
				.anyMatch(line -> courseId.equals(line.getCourseId())))
			.toList();
		if (matching.isEmpty()) {
			return List.of();
		}
		List<UUID> matchingIds = matching.stream().map(StudentOrder::getId).toList();
		Map<UUID, List<Payment>> paymentsByOrder = new HashMap<>();
		paymentRepository.findAllByOrderIdIn(matchingIds)
			.forEach(p -> paymentsByOrder.computeIfAbsent(p.getOrderId(), id -> new ArrayList<>()).add(p));
		Map<UUID, List<PaymentSlip>> slipsByOrder = new HashMap<>();
		slipRepository.findAllByOrderIdIn(matchingIds)
			.forEach(s -> slipsByOrder.computeIfAbsent(s.getOrderId(), id -> new ArrayList<>()).add(s));
		Map<UUID, ManualPaymentReceipt> receiptByPayment = new HashMap<>();
		receiptRepository.findAllByOrderIdIn(matchingIds).forEach(r -> receiptByPayment.put(r.getPaymentId(), r));

		Set<UUID> courseIds = new LinkedHashSet<>();
		matching.forEach(order -> linesByOrder.get(order.getId()).forEach(line -> courseIds.add(line.getCourseId())));
		Map<UUID, String> titles = support.courseTitles(courseIds);
		Map<UUID, String> staffNames = staffNames(receiptByPayment.values());

		List<HistoryEntry> result = new ArrayList<>();
		for (StudentOrder order : matching) {
			HistoryEntry entry = toEntry(order, linesByOrder.get(order.getId()),
					paymentsByOrder.getOrDefault(order.getId(), List.of()),
					slipsByOrder.getOrDefault(order.getId(), List.of()), receiptByPayment, titles, staffNames);
			if (entry != null) {
				result.add(entry);
			}
		}
		result.sort(Comparator.comparing(HistoryEntry::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(HistoryEntry::orderId));
		return List.copyOf(result);
	}

	private HistoryEntry toEntry(StudentOrder order, List<StudentOrderLine> lines, List<Payment> payments,
			List<PaymentSlip> slips, Map<UUID, ManualPaymentReceipt> receiptByPayment, Map<UUID, String> titles,
			Map<UUID, String> staffNames) {
		Comparator<Payment> newestPayment = Comparator.comparing(Payment::getCreatedAt,
				Comparator.nullsFirst(Comparator.naturalOrder()));
		Optional<Payment> confirmed = payments.stream()
			.filter(p -> p.getStatus() == PaymentStatus.CONFIRMED || p.getStatus() == PaymentStatus.REFUNDED)
			.max(newestPayment);
		Optional<Payment> latest = payments.stream().max(newestPayment);
		Optional<PaymentSlip> latestSlip = slips.stream()
			.max(Comparator.comparing(PaymentSlip::getSubmittedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
		boolean approvedSlip = slips.stream().anyMatch(s -> s.getStatus() == PaymentSlipStatus.APPROVED);
		boolean openSlip = slips.stream()
			.anyMatch(s -> s.getStatus() == PaymentSlipStatus.SUBMITTED || s.getStatus() == PaymentSlipStatus.UNDER_REVIEW);
		boolean pendingPayment = payments.stream().anyMatch(p -> p.getStatus() == PaymentStatus.PENDING);
		boolean rejected = slips.stream().anyMatch(s -> s.getStatus() == PaymentSlipStatus.REJECTED)
				|| payments.stream().anyMatch(p -> p.getStatus() == PaymentStatus.REJECTED);

		String status;
		if (confirmed.isPresent() || approvedSlip) {
			status = "RECEIVED";
		}
		else if (openSlip) {
			status = "PENDING_SLIP";
		}
		else if (pendingPayment) {
			status = "PENDING_ONLINE";
		}
		else if (rejected) {
			status = "REJECTED";
		}
		else {
			return null;
		}
		String source = source(confirmed.orElse(null), !slips.isEmpty(), pendingPayment);
		ManualPaymentReceipt receipt = confirmed.map(p -> receiptByPayment.get(p.getId())).orElse(null);
		BigDecimal refunded = confirmed.map(p -> refundRepository.findAllByOriginalPaymentId(p.getId())
			.stream()
			.map(PaymentRefund::getAmount)
			.reduce(ZERO, BigDecimal::add)).orElse(ZERO);
		HistoryReceipt receiptView = receipt == null ? null
				: new HistoryReceipt(receipt.getId(), receipt.getPaymentMethod().name(), receipt.getReferenceNumber(),
						receipt.getReceivedOn(), receipt.getNotes(), receipt.getAdjustmentReason(),
						receipt.getRecordedBy(), staffNames.get(receipt.getRecordedBy()), receipt.getRecordedAt());
		UUID paymentId = confirmed.or(() -> latest).map(Payment::getId).orElse(null);
		return new HistoryEntry(order.getId(), order.getCreatedAt(), status, source, order.getCurrency(),
				order.getAmount(), confirmed.map(Payment::getAmount).orElse(null),
				receipt == null ? ZERO : receipt.getStaffAdjustmentAmount(), refunded, paymentId,
				latestSlip.map(PaymentSlip::getId).orElse(null), receiptView, support.toLines(lines, titles));
	}

	/** MANUAL / SLIP / GATEWAY / OTHER from the synthesized gateway-reference prefixes (see {@code PaymentMethod}). */
	static String source(Payment confirmed, boolean hasSlip, boolean pendingPayment) {
		if (confirmed != null && confirmed.getGatewayReference() != null) {
			String reference = confirmed.getGatewayReference();
			if (reference.startsWith(ManualPaymentService.GATEWAY_REFERENCE_PREFIX)) {
				return "MANUAL";
			}
			if (reference.startsWith("SLIP-")) {
				return "SLIP";
			}
			if (reference.startsWith("FREE-") || reference.startsWith("STAFF_GRANTED-")) {
				return "OTHER";
			}
			return "GATEWAY";
		}
		if (hasSlip) {
			return "SLIP";
		}
		return pendingPayment ? "GATEWAY" : "OTHER";
	}

	/** Best-effort staff display names (the tenant_user email, as the audit Activity view does). */
	private Map<UUID, String> staffNames(Collection<ManualPaymentReceipt> receipts) {
		Set<UUID> ids = new LinkedHashSet<>();
		receipts.forEach(r -> ids.add(r.getRecordedBy()));
		Map<UUID, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(ids)) {
				names.put(summary.userId(), summary.email());
			}
		}
		return names;
	}

	private void requireView() {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
	}

}
