package com.lms.exammanagement.service.exam;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.FreeCardFact;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamEnrollmentSource;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.exam.ExamPaymentCriteriaMatcher.OrderFacts;
import com.lms.exammanagement.service.exam.ExamViews.PaymentCriteriaView;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentRefundReportApi;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
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
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-032 payment auto-enrollment (decision D5, change-controlled, approved): applies the
 * {@link ExamPaymentCriteriaMatcher} to CONFIRMED payments and Free Cards, and handles refunds.
 *
 * <ul>
 * <li>Tenant is always the resolved {@link TenantContext} - set from the event's own tenant by
 * {@code ExamPaymentEventListener}, or the staff caller's tenant for "Sync payments".</li>
 * <li>Payment state is read-only input via {@code payment-management}'s api; nothing is ever
 * written to payment, ledger or course-enrollment tables. The payment is independently
 * re-verified as CONFIRMED for its order before anything is enrolled.</li>
 * <li>Only live exams of the paid course with {@code auto_enroll_on_payment} and {@code now <
 * endAt} are touched; inserts are {@code ON CONFLICT DO NOTHING}, so replays, multi-course orders
 * and concurrent deliveries are idempotent.</li>
 * <li>Refund / chargeback: a PAYMENT enrollment of that payment is removed only while the
 * student has no attempt for the exam (checked under the exam's lock); otherwise kept.</li>
 * </ul>
 */
@Service
public class ExamPaymentEnrollmentService {

	private static final Logger log = LoggerFactory.getLogger(ExamPaymentEnrollmentService.class);

	static final int MAX_FREE_CARD_FACTS = 100_000;

	private final ExamRepository examRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamParticipationReader participationReader;

	private final PaymentStatusApi paymentStatusApi;

	private final PaymentRefundReportApi refundApi;

	private final CourseLookupApi courseLookupApi;

	private final CourseSummaryEnrollmentReportApi freeCardApi;

	private final TenantConfigApi tenantConfigApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	private final ObjectMapper objectMapper;

	public ExamPaymentEnrollmentService(ExamRepository examRepository, ExamEnrollmentRepository enrollmentRepository,
			ExamParticipationReader participationReader, PaymentStatusApi paymentStatusApi,
			PaymentRefundReportApi refundApi, CourseLookupApi courseLookupApi,
			CourseSummaryEnrollmentReportApi freeCardApi, TenantConfigApi tenantConfigApi, AuditLogApi auditLogApi,
			TenantContext tenantContext, Clock clock, ObjectMapper objectMapper) {
		this.auditLogApi = auditLogApi;
		this.examRepository = examRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.participationReader = participationReader;
		this.paymentStatusApi = paymentStatusApi;
		this.refundApi = refundApi;
		this.courseLookupApi = courseLookupApi;
		this.freeCardApi = freeCardApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	// ------------------------------------------------------------------
	// Payment confirmed (event listener).
	// ------------------------------------------------------------------

	/**
	 * Enrolls the payer into every matching auto-enroll exam of the courses the order pays for.
	 * Runs in its own transaction after the payment transaction committed.
	 * @return the number of new enrollments.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int enrollForConfirmedPayment(UUID paymentId, UUID orderId) {
		if (!paymentStatusApi.isConfirmedForOrder(paymentId, orderId)) {
			log.atWarn()
				.setMessage("exam.auto_enroll.payment_not_confirmed")
				.addKeyValue("examTenantId", tenantContext.getTenantId())
				.addKeyValue("paymentId", paymentId)
				.log();
			return 0;
		}
		OrderPaymentDetail detail = paymentStatusApi.findOrderPaymentDetails(List.of(orderId))
			.stream()
			.findFirst()
			.orElse(null);
		if (detail == null || detail.studentId() == null) {
			return 0;
		}
		List<OrderLineSnapshot> lines = paymentStatusApi.findOrderLines(orderId);
		UUID legacyCourseId = lines.isEmpty() ? legacyCourseOf(orderId, detail) : null;
		Set<UUID> courseIds = new HashSet<>();
		lines.stream().map(OrderLineSnapshot::courseId).filter(Objects::nonNull).forEach(courseIds::add);
		if (legacyCourseId != null) {
			courseIds.add(legacyCourseId);
		}
		Instant now = clock.instant();
		List<Exam> exams = autoEnrollExams(courseIds, now);
		int added = 0;
		Map<UUID, BigDecimal> cashByCourse = new HashMap<>();
		for (Exam exam : exams) {
			PaymentCriteriaView criteria = criteria(exam);
			BigDecimal cash = ExamPaymentCriteriaMatcher.needsCumulativeCash(criteria) ? cashByCourse
				.computeIfAbsent(exam.getCourseId(), course -> cumulativeCourseCash(detail.studentId(), course)) : null;
			OrderFacts facts = new OrderFacts(lines, legacyCourseId, lessonDatesIfNeeded(criteria, exam), cash);
			if (ExamPaymentCriteriaMatcher.matches(criteria, exam.getCourseId(), facts)) {
				added += enrollmentRepository.insertIfAbsent(exam.getId(), detail.studentId(),
						ExamEnrollmentSource.PAYMENT, paymentId, null, null, now);
			}
		}
		log.atInfo()
			.setMessage("exam.auto_enroll.payment_processed")
			.addKeyValue("examTenantId", tenantContext.getTenantId())
			.addKeyValue("paymentId", paymentId)
			.addKeyValue("examsConsidered", exams.size())
			.addKeyValue("added", added)
			.log();
		return added;
	}

	// ------------------------------------------------------------------
	// Refund / chargeback (event listener).
	// ------------------------------------------------------------------

	/**
	 * Removes the PAYMENT enrollments sourced from {@code paymentId} whose student has no attempt;
	 * keeps the others (academic record preserved). Returns the removed enrollments.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<ExamEnrollment> removeForRefundedPayment(UUID paymentId, UUID actorUserId, UUID refundId) {
		List<ExamEnrollment> removed = new ArrayList<>();
		for (ExamEnrollment enrollment : enrollmentRepository.findAllByPaymentIdAndSource(paymentId,
				ExamEnrollmentSource.PAYMENT)) {
			examRepository.acquireExamConfigLock(ExamLocks.configKey(tenantContext.getTenantId(), enrollment.getExamId()));
			if (participationReader.hasStudentParticipation(enrollment.getExamId(), enrollment.getStudentId())) {
				log.atInfo()
					.setMessage("exam.auto_enroll.refund_kept_attempted")
					.addKeyValue("examTenantId", tenantContext.getTenantId())
					.addKeyValue("examId", enrollment.getExamId())
					.addKeyValue("paymentId", paymentId)
					.log();
				continue;
			}
			enrollmentRepository.deleteEnrollment(enrollment.getId());
			removed.add(enrollment);
			// Same transaction as the delete. A chargeback has no human actor (system path):
			// the structured log below is its record, matching the payment module's convention.
			if (actorUserId != null) {
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("studentId", enrollment.getStudentId().toString());
				metadata.put("source", enrollment.getSource().name());
				metadata.put("paymentId", paymentId.toString());
				if (refundId != null) {
					metadata.put("refundId", refundId.toString());
				}
				auditLogApi.record(new AuditLogEntry(actorUserId, "exam.enrollment_removed_on_refund", "exam",
						enrollment.getExamId(), null, metadata));
			}
			log.atInfo()
				.setMessage("exam.auto_enroll.refund_removed")
				.addKeyValue("examTenantId", tenantContext.getTenantId())
				.addKeyValue("examId", enrollment.getExamId())
				.addKeyValue("studentId", enrollment.getStudentId())
				.addKeyValue("paymentId", paymentId)
				.log();
		}
		return removed;
	}

	// ------------------------------------------------------------------
	// Staff "Sync payments" and Free Cards (caller's transaction).
	// ------------------------------------------------------------------

	/** ADR-032 §5: the same matcher over the course's already-confirmed payments plus Free Cards. */
	@Transactional
	public int syncExam(Exam exam, UUID actorId) {
		Instant now = clock.instant();
		if (!exam.isAutoEnrollOnPayment() || !now.isBefore(exam.getScheduledEnd())) {
			return 0;
		}
		PaymentCriteriaView criteria = criteria(exam);
		UUID courseId = exam.getCourseId();
		List<OrderPaymentDetail> confirmed = paymentStatusApi
			.findOrderPaymentDetails(paymentStatusApi.findOrderIdsForCourse(courseId))
			.stream()
			.filter(detail -> detail.confirmedPaymentId() != null && detail.studentId() != null)
			.toList();
		Map<UUID, List<OrderLineSnapshot>> linesByOrder = paymentStatusApi
			.findOrderLinesForOrders(confirmed.stream().map(OrderPaymentDetail::orderId).toList())
			.stream()
			.collect(Collectors.groupingBy(OrderLineSnapshot::orderId, LinkedHashMap::new, Collectors.toList()));
		Map<UUID, LocalDate> lessonDates = lessonDatesIfNeeded(criteria, exam);
		Map<UUID, BigDecimal> cashByStudent = new HashMap<>();
		int added = 0;
		for (OrderPaymentDetail detail : confirmed) {
			List<OrderLineSnapshot> lines = linesByOrder.getOrDefault(detail.orderId(), List.of());
			UUID legacyCourseId = lines.isEmpty() ? legacyCourseOf(detail.orderId(), detail) : null;
			BigDecimal cash = ExamPaymentCriteriaMatcher.needsCumulativeCash(criteria) ? cashByStudent
				.computeIfAbsent(detail.studentId(), student -> cumulativeCourseCash(student, courseId)) : null;
			if (ExamPaymentCriteriaMatcher.matches(criteria, courseId,
					new OrderFacts(lines, legacyCourseId, lessonDates, cash))) {
				added += enrollmentRepository.insertIfAbsent(exam.getId(), detail.studentId(),
						ExamEnrollmentSource.PAYMENT, detail.confirmedPaymentId(), null, actorId, now);
			}
		}
		added += enrollFreeCards(exam, criteria, null, actorId, now);
		return added;
	}

	/**
	 * ADR-030 / D5: Free Cards qualify for month criteria. Enrolls holders of an unrevoked card
	 * overlapping a criteria month ({@code onlyStudentId != null}: that student only). Exams
	 * that ended or are not MONTHS auto-enroll are ignored.
	 */
	int enrollFreeCards(Exam exam, PaymentCriteriaView criteria, UUID onlyStudentId, UUID actorId, Instant now) {
		if (!exam.isAutoEnrollOnPayment() || !now.isBefore(exam.getScheduledEnd()) || criteria == null
				|| !ExamPaymentCriteriaMatcher.MONTHS.equals(criteria.kind())) {
			return 0;
		}
		Set<YearMonth> months = ExamPaymentCriteriaMatcher.months(criteria);
		if (months.isEmpty()) {
			return 0;
		}
		LocalDate from = months.stream().min(YearMonth::compareTo).orElseThrow().atDay(1);
		LocalDate to = months.stream().max(YearMonth::compareTo).orElseThrow().atEndOfMonth();
		int added = 0;
		for (FreeCardFact card : freeCardApi.findFreeCardFacts(exam.getCourseId(), from, to, MAX_FREE_CARD_FACTS)) {
			if (onlyStudentId != null && !onlyStudentId.equals(card.studentId())) {
				continue;
			}
			if (ExamPaymentCriteriaMatcher.freeCardCovers(criteria, card.validFrom(), card.validTo(), card.revoked())) {
				added += enrollmentRepository.insertIfAbsent(exam.getId(), card.studentId(),
						ExamEnrollmentSource.PAYMENT, null, card.cardId(), actorId, now);
			}
		}
		return added;
	}

	PaymentCriteriaView criteria(Exam exam) {
		if (exam.getPaymentCriteria() == null) {
			return null;
		}
		return objectMapper.readValue(exam.getPaymentCriteria(), PaymentCriteriaView.class);
	}

	// ------------------------------------------------------------------
	// Facts.
	// ------------------------------------------------------------------

	private List<Exam> autoEnrollExams(Collection<UUID> courseIds, Instant now) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return examRepository.findLiveKlassExams(Set.copyOf(courseIds))
			.stream()
			.filter(exam -> exam.isAutoEnrollOnPayment() && now.isBefore(exam.getScheduledEnd()))
			.toList();
	}

	private UUID legacyCourseOf(UUID orderId, OrderPaymentDetail detail) {
		return paymentStatusApi.findOrderRevenueBases(List.of(orderId))
			.stream()
			.findFirst()
			.filter(basis -> !basis.lineOrder())
			.map(OrderRevenueBasis::legacyCourseId)
			.orElse(detail.courseId());
	}

	/**
	 * The student's cumulative confirmed cash for {@code courseId}: course shares of line orders
	 * (ADR-029: never facility fees or postage; reward lines are worth 0) plus the full amount of
	 * legacy single-course orders, each net of what has been refunded on its confirmed payment
	 * (staff refunds and chargebacks; the payment row itself stays CONFIRMED). A partially refunded
	 * line order keeps the unrefunded fraction of each course share. Free Cards are never cash.
	 */
	private BigDecimal cumulativeCourseCash(UUID studentId, UUID courseId) {
		Map<UUID, UUID> paymentByOrder = new HashMap<>();
		paymentStatusApi.findOrderPaymentDetails(paymentStatusApi.findOrderIdsForStudent(studentId))
			.stream()
			.filter(detail -> detail.confirmedPaymentId() != null)
			.forEach(detail -> paymentByOrder.put(detail.orderId(), detail.confirmedPaymentId()));
		if (paymentByOrder.isEmpty()) {
			return BigDecimal.ZERO;
		}
		Map<UUID, BigDecimal> refunded = refundApi.findRefundedAmountsByPaymentIds(paymentByOrder.values());
		BigDecimal total = BigDecimal.ZERO;
		for (OrderRevenueBasis basis : paymentStatusApi.findOrderRevenueBases(List.copyOf(paymentByOrder.keySet()))) {
			BigDecimal refund = refunded.getOrDefault(paymentByOrder.get(basis.orderId()), BigDecimal.ZERO);
			if (basis.lineOrder()) {
				BigDecimal share = basis.courseShares().getOrDefault(courseId, BigDecimal.ZERO);
				total = total.add(netOfRefund(share, basis.orderAmount(), refund));
			}
			else if (courseId.equals(basis.legacyCourseId()) && basis.orderAmount() != null) {
				total = total.add(nonNegative(basis.orderAmount().subtract(refund)));
			}
		}
		return total;
	}

	/** {@code share} scaled by the unrefunded fraction of {@code orderAmount}. */
	static BigDecimal netOfRefund(BigDecimal share, BigDecimal orderAmount, BigDecimal refund) {
		if (refund.signum() <= 0) {
			return share;
		}
		if (orderAmount == null || orderAmount.signum() <= 0 || refund.compareTo(orderAmount) >= 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal remaining = orderAmount.subtract(refund);
		return share.multiply(remaining).divide(orderAmount, 2, RoundingMode.HALF_UP);
	}

	private static BigDecimal nonNegative(BigDecimal amount) {
		return amount.signum() < 0 ? BigDecimal.ZERO : amount;
	}

	private Map<UUID, LocalDate> lessonDatesIfNeeded(PaymentCriteriaView criteria, Exam exam) {
		if (criteria == null || !ExamPaymentCriteriaMatcher.DATES.equals(criteria.kind())) {
			return Map.of();
		}
		ZoneId zone = tenantZone();
		Map<UUID, LocalDate> dates = new HashMap<>();
		for (LessonPolicy lesson : courseLookupApi.findLessonPolicies(exam.getCourseId())) {
			if (lesson.startsAt() != null) {
				dates.put(lesson.lessonId(), LocalDate.ofInstant(lesson.startsAt(), zone));
			}
		}
		return dates;
	}

	private ZoneId tenantZone() {
		Object configured = tenantConfigApi
			.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zone && !zone.isBlank()) {
			try {
				return ZoneId.of(zone);
			}
			catch (DateTimeException ignored) {
				// Registry validation makes this unreachable; fall back to UTC.
			}
		}
		return ZoneId.of("UTC");
	}

}
