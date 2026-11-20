package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerApi;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.OrderCourseCharge;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentReportApi;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.reportinganalytics.config.StudentPaymentReportProperties;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.GroupBy;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.RowsPage;
import com.lms.reportinganalytics.service.StudentPaymentReport.CourseRef;
import com.lms.reportinganalytics.service.pdf.StudentPaymentPdfRenderer;
import com.lms.reportinganalytics.service.support.ReportPeriodRules;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Mode;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Period;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.StudentPaymentRunRequest;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPT-02 Student Payment Report - orchestration only (rules live in {@link
 * StudentPaymentCalculator} and {@link StudentPaymentGrouping}). Every
 * operation checks {@code FINANCE_EXPENSES}/{@code VIEW} FIRST, before any
 * input is parsed or any data is read. The tenant always comes from the
 * resolved {@link TenantContext} and the run owner from the authenticated
 * principal - never from the request. Strictly read-only: read-only
 * transactions, and nothing is ever written to payment, ledger, settlement,
 * enrollment or entitlement tables (the only write is the ephemeral Redis run
 * snapshot). Pages and the PDF read only the stored run of the same tenant and
 * user and never recompute.
 */
@Service
public class StudentPaymentReportService {

	public static final List<String> PAYMENT_METHODS = ReportPeriodRules.FILTERABLE_METHODS.stream()
		.map(PaymentMethod::name)
		.toList();

	public static final List<String> GROUP_BY_MODES = Arrays.stream(GroupBy.values()).map(GroupBy::name).toList();

	public static final int MAX_PAGE_SIZE = 200;

	public static final int DEFAULT_PAGE_SIZE = 50;

	private static final String NARROW_HINT = "choose a shorter period or fewer courses";

	private final PermissionCheckService permissionCheckService;

	private final StudentPaymentRequestValidator validator;

	private final ReportLedgerApi ledgerApi;

	private final PaymentReportApi paymentReportApi;

	private final PaymentStatusApi paymentStatusApi;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final ReportTenantInfo tenantInfo;

	private final TenantContext tenantContext;

	private final StudentPaymentRunStore runStore;

	private final StudentPaymentPdfRenderer pdfRenderer;

	private final StudentPaymentReportProperties properties;

	public StudentPaymentReportService(PermissionCheckService permissionCheckService,
			StudentPaymentRequestValidator validator, ReportLedgerApi ledgerApi, PaymentReportApi paymentReportApi,
			PaymentStatusApi paymentStatusApi, CourseLookupApi courseLookupApi, StudentLookupApi studentLookupApi,
			ReportTenantInfo tenantInfo, TenantContext tenantContext, StudentPaymentRunStore runStore,
			StudentPaymentPdfRenderer pdfRenderer, StudentPaymentReportProperties properties) {
		this.permissionCheckService = permissionCheckService;
		this.validator = validator;
		this.ledgerApi = ledgerApi;
		this.paymentReportApi = paymentReportApi;
		this.paymentStatusApi = paymentStatusApi;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.tenantInfo = tenantInfo;
		this.tenantContext = tenantContext;
		this.runStore = runStore;
		this.pdfRenderer = pdfRenderer;
		this.properties = properties;
	}

	/** {@code GET /options}: the tenant's courses (id, name) and the filter enums. */
	public record Options(List<CourseRef> courses, List<String> paymentMethods, List<String> groupByModes) {

	}

	@Transactional(readOnly = true)
	public Options options() {
		requireView();
		List<CourseRef> courses = courseLookupApi.listCourseSummaries()
			.stream()
			.map(course -> new CourseRef(course.id(), course.name()))
			.toList();
		return new Options(courses, PAYMENT_METHODS, GROUP_BY_MODES);
	}

	@Transactional(readOnly = true)
	public StudentPaymentReport createRun(StudentPaymentRunRequest request) {
		requireView();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		UUID tenantId = tenantContext.getTenantId();
		runStore.acquireRunSlot(tenantId, userId);
		StudentPaymentCriteria criteria = validator.validate(request);
		try {
			return computeAndStore(tenantId, userId, criteria);
		}
		catch (ReportScopeTooLargeException ex) {
			throw ReportValidationException.tooLarge("period", "covers " + ex.getEntryCount()
					+ " ledger entries; at most " + ex.getMaxEntries() + " are allowed - " + NARROW_HINT);
		}
	}

	public StudentPaymentReport getRun(String runId) {
		requireView();
		return loadRun(runId).report();
	}

	/** One page (0-based) of the stored run's rows in {@code groupBy} order, with group subtotals. */
	public RowsPage rows(String runId, String groupBy, String page, String size) {
		requireView();
		GroupBy by = GroupBy.parse(groupBy);
		Integer pageNo = parseInt(page, 0);
		Integer pageSize = parseInt(size, DEFAULT_PAGE_SIZE);
		List<FieldError> errors = new ArrayList<>();
		if (pageNo == null || pageNo < 0) {
			errors.add(new FieldError("page", "must be a whole number, 0 or greater"));
		}
		if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			errors.add(new FieldError("size", "must be a whole number between 1 and " + MAX_PAGE_SIZE));
		}
		if (!errors.isEmpty()) {
			throw new ReportValidationException(errors);
		}
		runStore.acquireRowsSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());
		StudentPaymentRun run = loadRun(runId);
		return StudentPaymentGrouping.page(StudentPaymentGrouping.group(run.rows(), by), pageNo, pageSize);
	}

	/** Landscape PDF of ALL rows of the stored run (never recomputed); generating it writes nothing. */
	public byte[] exportPdf(String runId, String groupBy) {
		requireView();
		GroupBy by = GroupBy.parse(groupBy);
		runStore.acquirePdfSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());
		StudentPaymentRun run = loadRun(runId);
		if (run.rows().size() > properties.getMaxPdfRows()) {
			throw ReportValidationException.tooLarge("runId", "has " + run.rows().size() + " rows; at most "
					+ properties.getMaxPdfRows() + " can be exported to PDF - " + NARROW_HINT);
		}
		return pdfRenderer.render(run, StudentPaymentGrouping.group(run.rows(), by));
	}

	// ------------------------------------------------------------------

	private StudentPaymentReport computeAndStore(UUID tenantId, UUID userId, StudentPaymentCriteria criteria) {
		int maxEntries = properties.getMaxLedgerEntries();
		ZoneId zone = tenantInfo.zone(tenantId);
		Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Period period = criteria.period();

		List<ReportLedgerEntry> entries = new ArrayList<>();
		if (period.mode() == Mode.DATES) {
			entries.addAll(ledgerApi.findEntriesCreatedIn(
					List.of(ReportPeriodRules.dateWindow(period.startDate(), period.endDate(), zone)), maxEntries));
			// 3-R.4: manual receipts are in scope by received_on, which may lie
			// outside the ledger window (backdated receipts) - load them too.
			Set<UUID> loadedConfirmations = entries.stream()
				.filter(entry -> !entry.refund() && entry.paymentId() != null)
				.map(ReportLedgerEntry::paymentId)
				.collect(Collectors.toSet());
			// Count only confirmations not already loaded by the ledger window. A
			// receipt recorded and received on the same day consumes the bound once.
			long receivedCount = paymentReportApi.countManualReceiptsReceivedBetween(period.startDate(),
					period.endDate());
			long overlap = paymentReportApi.countManualReceiptsReceivedBetweenForPaymentIds(period.startDate(),
					period.endDate(), loadedConfirmations);
			long additional = Math.max(0, receivedCount - overlap);
			if (entries.size() + additional > maxEntries) {
				throw new ReportScopeTooLargeException(entries.size() + additional, maxEntries);
			}
			List<UUID> received = paymentReportApi
				.findManualReceiptPaymentIdsReceivedBetween(period.startDate(), period.endDate())
				.stream()
				.filter(id -> !loadedConfirmations.contains(id))
				.toList();
			if (!received.isEmpty()) {
				try {
					entries.addAll(ledgerApi.findConfirmationsForPayments(received,
							Math.max(0, maxEntries - entries.size())));
				}
				catch (ReportScopeTooLargeException ex) {
					throw new ReportScopeTooLargeException(entries.size() + ex.getEntryCount(), maxEntries);
				}
			}
		}
		else {
			entries.addAll(ledgerApi.findEntriesForBillingMonths(period.months(), maxEntries));
		}

		Set<UUID> orderIds = new LinkedHashSet<>();
		Set<UUID> manualPaymentIds = new LinkedHashSet<>();
		Set<UUID> courseIds = new HashSet<>(criteria.courseIds());
		for (ReportLedgerEntry entry : entries) {
			orderIds.add(entry.orderId());
			if (entry.method() == PaymentMethod.MANUAL_RECEIPT && entry.paymentId() != null) {
				manualPaymentIds.add(entry.paymentId());
			}
			for (ReportCourseAllocation allocation : entry.courses()) {
				courseIds.add(allocation.courseId());
			}
		}
		List<OrderLineSnapshot> lines = orderIds.isEmpty() ? List.of()
				: paymentStatusApi.findOrderLinesForOrders(orderIds);
		List<OrderCourseCharge> charges = orderIds.isEmpty() ? List.of()
				: paymentReportApi.findOrderCourseCharges(orderIds);
		Map<UUID, UUID> studentByOrder = orderIds.isEmpty() ? Map.of()
				: paymentReportApi.findOrderStudentIds(orderIds);
		List<ManualReceiptFact> receipts = manualPaymentIds.isEmpty() ? List.of()
				: paymentReportApi.findManualReceiptsByPaymentIds(manualPaymentIds);
		Set<UUID> lessonIds = new HashSet<>();
		Set<UUID> studentIds = new HashSet<>(studentByOrder.values());
		for (OrderLineSnapshot line : lines) {
			courseIds.add(line.courseId());
			if (line.studentId() != null) {
				studentIds.add(line.studentId());
			}
			if ("LESSON".equals(line.scope()) && line.lessonId() != null) {
				lessonIds.add(line.lessonId());
			}
		}
		Map<UUID, YearMonth> lessonMonths = lessonIds.isEmpty() ? Map.of()
				: courseLookupApi.findLessonBillingMonths(lessonIds);
		Map<UUID, String> courseNames = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getCourseSummaries(courseIds)
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));
		List<StudentIndexEntry> students = studentIds.isEmpty() ? List.of()
				: studentLookupApi.findStudentIndexEntries(studentIds);
		List<CourseRef> selected = criteria.courseIds()
			.stream()
			.map(id -> new CourseRef(id, courseNames.getOrDefault(id, "Unknown course")))
			.toList();

		StudentPaymentCalculator.Result result = StudentPaymentCalculator
			.calculate(new StudentPaymentCalculator.Input(criteria, zone, generatedAt, tenantInfo.instituteName(tenantId),
					selected, entries, lines, charges, studentByOrder, receipts, lessonMonths, courseNames, students,
					tenantInfo.defaultCurrency(tenantId)));
		if (result.rows().size() > properties.getMaxRows()) {
			throw ReportValidationException.tooLarge("period", "has " + result.rows().size() + " rows; at most "
					+ properties.getMaxRows() + " are allowed - " + NARROW_HINT);
		}
		StudentPaymentReport report = result.report()
			.withRun(UUID.randomUUID(), generatedAt.plus(ReportRunSnapshotStore.TTL));
		runStore.save(tenantId, userId, new StudentPaymentRun(report, result.rows()));
		return report;
	}

	private static Integer parseInt(String value, int fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Integer.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private void requireView() {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
	}

	private StudentPaymentRun loadRun(String runId) {
		UUID id;
		try {
			id = UUID.fromString(runId);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new NotFoundException("Report run not found");
		}
		return runStore.find(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId(), id)
			.orElseThrow(() -> new NotFoundException("Report run not found"));
	}

}
