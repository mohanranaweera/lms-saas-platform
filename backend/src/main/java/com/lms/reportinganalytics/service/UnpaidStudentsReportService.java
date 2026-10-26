package com.lms.reportinganalytics.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.attendancemanagement.api.AttendanceReportQueryApi;
import com.lms.common.api.FieldError;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi.ParticipationFact;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.ReportLedgerApi;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.reportinganalytics.config.UnpaidStudentsReportProperties;
import com.lms.reportinganalytics.service.UnpaidStudentsGrouping.RowsPage;
import com.lms.reportinganalytics.service.pdf.UnpaidStudentsPdfRenderer;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.UnpaidStudentsRunRequest;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only orchestration for Unpaid Students Report. Qualification uses only
 * immutable order-line snapshots and signed ledger allocation facts; Free Cards
 * and paid learning access are intentionally not consulted.
 */
@Service
public class UnpaidStudentsReportService {

	public static final int MAX_PAGE_SIZE = 200;
	public static final int DEFAULT_PAGE_SIZE = 50;
	private static final String TEACHER_ROLE = "TEACHER";

	private final PermissionCheckService permissionCheckService;
	private final UnpaidStudentsRequestValidator validator;
	private final UnpaidStudentsEnrollmentApi enrollmentApi;
	private final PaymentStatusApi paymentStatusApi;
	private final ReportLedgerApi ledgerApi;
	private final AttendanceReportQueryApi attendanceApi;
	private final CourseLookupApi courseLookupApi;
	private final StudentLookupApi studentLookupApi;
	private final ReportTenantInfo tenantInfo;
	private final TenantContext tenantContext;
	private final UnpaidStudentsRunStore runStore;
	private final UnpaidStudentsPdfRenderer pdfRenderer;
	private final UnpaidStudentsReportProperties properties;

	public UnpaidStudentsReportService(PermissionCheckService permissionCheckService,
			UnpaidStudentsRequestValidator validator, UnpaidStudentsEnrollmentApi enrollmentApi,
			PaymentStatusApi paymentStatusApi, ReportLedgerApi ledgerApi, AttendanceReportQueryApi attendanceApi,
			CourseLookupApi courseLookupApi, StudentLookupApi studentLookupApi, ReportTenantInfo tenantInfo,
			TenantContext tenantContext, UnpaidStudentsRunStore runStore, UnpaidStudentsPdfRenderer pdfRenderer,
			UnpaidStudentsReportProperties properties) {
		this.permissionCheckService = permissionCheckService;
		this.validator = validator;
		this.enrollmentApi = enrollmentApi;
		this.paymentStatusApi = paymentStatusApi;
		this.ledgerApi = ledgerApi;
		this.attendanceApi = attendanceApi;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.tenantInfo = tenantInfo;
		this.tenantContext = tenantContext;
		this.runStore = runStore;
		this.pdfRenderer = pdfRenderer;
		this.properties = properties;
	}

	public record Options(List<UnpaidStudentsReport.CourseRef> courses) {
	}

	@Transactional(readOnly = true)
	public Options options() {
		requireView();
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		List<CourseSummary> all = courseLookupApi.listCourseSummaries();
		Set<UUID> ids = all.stream().map(CourseSummary::id).collect(java.util.stream.Collectors.toSet());
		Map<UUID, UUID> teachers = courseLookupApi.getTeacherIdsByCourseId(ids);
		return new Options(all.stream()
			.filter(course -> !TEACHER_ROLE.equals(principal.role()) || principal.userId().equals(teachers.get(course.id())))
			.map(course -> new UnpaidStudentsReport.CourseRef(course.id(), course.name()))
			.toList());
	}

	@Transactional(readOnly = true)
	public UnpaidStudentsReport createRun(UnpaidStudentsRunRequest request) {
		requireView();
		UnpaidStudentsCriteria criteria = validator.validate(request);
		requireCourseAccess(criteria.courseId());
		UUID tenantId = tenantContext.getTenantId();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		runStore.acquireRunSlot(tenantId, userId);
		ZoneId zone = tenantInfo.zone(tenantId);
		Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		List<ParticipationFact> participants = enrollmentApi.findCurrentCourseParticipants(criteria.courseId(),
				properties.getMaxParticipants());
		if (participants.size() > properties.getMaxParticipants()) {
			throw ReportValidationException.tooLarge("courseId", "has more than " + properties.getMaxParticipants()
					+ " current participants; narrow report scope is unavailable for this course");
		}
		Set<UUID> studentIds = participants.stream().map(ParticipationFact::studentId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Map<UUID, List<UUID>> ordersByStudent = paymentStatusApi.findOrderIdsForStudents(studentIds);
		Set<UUID> orderIds = ordersByStudent.values().stream().flatMap(List::stream)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		List<OrderLineSnapshot> lines = orderIds.isEmpty() ? List.of() : paymentStatusApi.findOrderLinesForOrders(orderIds);
		List<com.lms.ledgersettlementmanagement.api.ReportLedgerEntry> ledger;
		try {
			ledger = orderIds.isEmpty() ? List.of() : ledgerApi.findEntriesForOrders(orderIds,
					properties.getMaxLedgerEntries());
		}
		catch (ReportScopeTooLargeException ex) {
			throw ReportValidationException.tooLarge("courseId", "covers " + ex.getEntryCount()
					+ " ledger entries; at most " + ex.getMaxEntries() + " are allowed");
		}
		Instant from = criteria.billingMonth().atDay(1).atStartOfDay(zone).toInstant();
		Instant to = criteria.billingMonth().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
		List<AttendanceReportFact> facts = attendanceApi.findReportFactsForFinancialReport(criteria.courseId(), from, to,
				properties.getMaxAttendanceFacts());
		if (facts.size() > properties.getMaxAttendanceFacts()) {
			throw ReportValidationException.tooLarge("billingMonth", "contains more than "
					+ properties.getMaxAttendanceFacts() + " attendance records");
		}
		List<UnpaidStudentProfile> profiles = studentLookupApi.findUnpaidStudentProfiles(studentIds);
		CourseSummary course = courseLookupApi.getCourseSummaries(Set.of(criteria.courseId())).stream().findFirst()
			.orElseThrow(() -> new NotFoundException("Course not found"));
		UnpaidStudentsCalculator.Result result = UnpaidStudentsCalculator.calculate(new UnpaidStudentsCalculator.Input(criteria,
				zone, generatedAt, tenantInfo.instituteName(tenantId), new UnpaidStudentsReport.CourseRef(course.id(), course.name()),
				participants, lines, ledger, facts, profiles));
		if (result.rows().size() > properties.getMaxRows()) {
			throw ReportValidationException.tooLarge("courseId", "has " + result.rows().size() + " unpaid students; at most "
					+ properties.getMaxRows() + " are allowed");
		}
		UnpaidStudentsReport report = result.report().withRun(UUID.randomUUID(), generatedAt.plus(runStore.ttl()));
		runStore.save(tenantId, userId, new UnpaidStudentsRun(report, result.rows()));
		return report;
	}

	public UnpaidStudentsReport getRun(String runId) {
		requireView();
		return loadRun(runId).report();
	}

	public RowsPage rows(String runId, String page, String size) {
		requireView();
		Integer pageNo = parseInt(page, 0);
		Integer pageSize = parseInt(size, DEFAULT_PAGE_SIZE);
		List<FieldError> errors = new ArrayList<>();
		if (pageNo == null || pageNo < 0) errors.add(new FieldError("page", "must be a whole number, 0 or greater"));
		if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			errors.add(new FieldError("size", "must be a whole number between 1 and " + MAX_PAGE_SIZE));
		}
		if (!errors.isEmpty()) throw new ReportValidationException(errors);
		runStore.acquireRowsSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());
		return UnpaidStudentsGrouping.page(loadRun(runId).rows(), pageNo, pageSize);
	}

	public byte[] exportPdf(String runId) {
		requireView();
		UUID tenantId = tenantContext.getTenantId();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		runStore.acquirePdfSlot(tenantId, userId);
		UnpaidStudentsRun run = loadRun(runId);
		if (run.rows().size() > properties.getMaxPdfRows()) {
			throw ReportValidationException.tooLarge("runId", "has " + run.rows().size() + " rows; at most "
					+ properties.getMaxPdfRows() + " can be exported to PDF");
		}
		return pdfRenderer.render(run);
	}

	private void requireView() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TEACHER_ROLE.equals(principal.role())) {
			permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		}
	}

	private void requireCourseAccess(UUID courseId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TEACHER_ROLE.equals(principal.role())) return;
		UUID teacherId = courseLookupApi.getTeacherId(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
		if (!principal.userId().equals(teacherId)) {
			throw new AccessDeniedException("You do not have permission to view this course's unpaid students report");
		}
	}

	private UnpaidStudentsRun loadRun(String runId) {
		UUID id;
		try { id = UUID.fromString(runId); }
		catch (IllegalArgumentException | NullPointerException ex) { throw new NotFoundException("Report run not found"); }
		return runStore.find(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId(), id)
			.orElseThrow(() -> new NotFoundException("Report run not found"));
	}

	private static Integer parseInt(String value, int fallback) {
		if (value == null || value.isBlank()) return fallback;
		try { return Integer.valueOf(value.trim()); }
		catch (NumberFormatException ex) { return null; }
	}

}
