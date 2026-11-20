package com.lms.reportinganalytics.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.attendancemanagement.api.AttendanceReportQueryApi;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi;
import com.lms.liveclassmanagement.api.CourseSessionReportApi;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentReportApi;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.reportinganalytics.config.CourseSummaryReportProperties;
import com.lms.reportinganalytics.service.CourseSummaryReport.CourseRef;
import com.lms.reportinganalytics.service.CourseSummaryReport.TeacherRef;
import com.lms.reportinganalytics.service.pdf.CourseSummaryPdfRenderer;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.CourseSummaryRunRequest;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RPT-05 orchestrator. It only writes short-lived, owner-bound report snapshots. */
@Service
public class CourseSummaryReportService {

	private final PermissionCheckService permissions;
	private final CourseSummaryRequestValidator validator;
	private final TeacherLookupApi teacherLookup;
	private final CourseLookupApi courseLookup;
	private final CourseSummaryEnrollmentReportApi enrollmentReport;
	private final CourseSessionReportApi sessionReport;
	private final AttendanceReportQueryApi attendanceReport;
	private final TeacherIncomeLedgerApi ledger;
	private final PaymentReportApi paymentReport;
	private final PaymentStatusApi paymentStatus;
	private final ReportTenantInfo tenantInfo;
	private final TenantContext tenantContext;
	private final CourseSummaryReportRunStore store;
	private final CourseSummaryPdfRenderer pdf;
	private final CourseSummaryReportProperties properties;

	public CourseSummaryReportService(PermissionCheckService permissions, CourseSummaryRequestValidator validator,
			TeacherLookupApi teacherLookup, CourseLookupApi courseLookup, CourseSummaryEnrollmentReportApi enrollmentReport,
			CourseSessionReportApi sessionReport, AttendanceReportQueryApi attendanceReport, TeacherIncomeLedgerApi ledger,
			PaymentReportApi paymentReport, PaymentStatusApi paymentStatus, ReportTenantInfo tenantInfo,
			TenantContext tenantContext, CourseSummaryReportRunStore store, CourseSummaryPdfRenderer pdf,
			CourseSummaryReportProperties properties) {
		this.permissions = permissions; this.validator = validator; this.teacherLookup = teacherLookup;
		this.courseLookup = courseLookup; this.enrollmentReport = enrollmentReport; this.sessionReport = sessionReport;
		this.attendanceReport = attendanceReport; this.ledger = ledger; this.paymentReport = paymentReport;
		this.paymentStatus = paymentStatus; this.tenantInfo = tenantInfo; this.tenantContext = tenantContext;
		this.store = store; this.pdf = pdf; this.properties = properties;
	}

	public record Options(List<TeacherRef> teachers) { }
	public record CourseOptions(List<CourseRef> courses) { }
	public record PdfExport(byte[] bytes, String filename) { }

	@Transactional(readOnly = true)
	public Options options() {
		requireView();
		return new Options(teacherLookup.listTeachers().stream().map(t -> new TeacherRef(t.userId(), display(t.name(), t.email()))).toList());
	}

	@Transactional(readOnly = true)
	public CourseOptions courses(UUID teacherId) {
		requireView();
		return new CourseOptions(courseLookup.listCourseSummariesByTeacherId(teacherId).stream().map(this::courseRef).toList());
	}

	@Transactional(readOnly = true)
	public CourseSummaryReport createRun(CourseSummaryRunRequest request) {
		requireView();
		CourseSummaryCriteria criteria = validator.validate(request);
		UUID tenantId = tenantContext.getTenantId();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		store.acquireRunSlot(tenantId, userId);
		ZoneId zone = tenantInfo.zone(tenantId);
		Instant from = criteria.startDate().atStartOfDay(zone).toInstant();
		Instant to = criteria.endDate().plusDays(1).atStartOfDay(zone).toInstant();
		try {
			List<ReportLedgerEntry> entries = ledger.findEntriesCreatedIn(List.of(new InstantRange(from, to)), properties.getMaxLedgerEntries());
			List<ReportLedgerEntry> courseEntries = entries.stream()
				.filter(entry -> entry.courses().stream().anyMatch(allocation -> criteria.courseId().equals(allocation.courseId())))
				.toList();
			List<AttendanceReportFact> attendance = attendanceReport.findReportFacts(criteria.courseId(), from, to, properties.getMaxFacts());
			var participation = enrollmentReport.findParticipationFacts(criteria.courseId(), from, to, properties.getMaxFacts());
			var freeCards = enrollmentReport.findFreeCardFacts(criteria.courseId(), criteria.startDate(), criteria.endDate(), properties.getMaxFacts());
			var sessions = sessionReport.findCourseSessions(criteria.courseId(), from, to, properties.getMaxFacts());
			ensureBound("attendance", attendance.size()); ensureBound("participation", participation.size()); ensureBound("free cards", freeCards.size()); ensureBound("classes", sessions.size());
			List<UUID> orderIds = courseEntries.stream()
				.map(ReportLedgerEntry::orderId)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList();
			Map<UUID, UUID> students = paymentReport.findOrderStudentIds(orderIds);
			List<OrderLineSnapshot> lines = paymentStatus.findOrderLinesForOrders(orderIds);
			TeacherRef teacher = teacherLookup.listTeachers().stream().filter(t -> criteria.teacherId().equals(t.userId())).findFirst()
				.map(t -> new TeacherRef(t.userId(), display(t.name(), t.email())))
				.orElseThrow(() -> new ReportValidationException(List.of(new com.lms.common.api.FieldError("teacherId", "teacher not found"))));
			CourseSummary course = courseLookup.getCourseSummaries(java.util.Set.of(criteria.courseId())).stream().findFirst()
				.orElseThrow(() -> new NotFoundException("Course not found"));
			Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
			CourseSummaryReport report = CourseSummaryCalculator.calculate(new CourseSummaryCalculator.Input(criteria, zone, generatedAt,
					tenantInfo.instituteName(tenantId), tenantInfo.defaultCurrency(tenantId), teacher, courseRef(course), participation,
					freeCards, sessions, attendance, courseEntries, students, lines, ledger.findShareRates(List.of(criteria.teacherId()))));
			report = report.withRun(UUID.randomUUID(), generatedAt.plus(store.ttl()));
			store.save(tenantId, userId, new CourseSummaryReportRun(report.runId(), report));
			return report;
		}
		catch (ReportScopeTooLargeException ex) { throw ReportValidationException.tooManyEntries(ex.getEntryCount(), ex.getMaxEntries()); }
	}

	public CourseSummaryReport getRun(String runId) { requireView(); return load(runId).report(); }

	public PdfExport exportPdf(String runId) {
		requireView(); UUID tenantId = tenantContext.getTenantId(); UUID userId = AuthenticatedPrincipalHolder.get().userId();
		store.acquirePdfSlot(tenantId, userId); CourseSummaryReport report = load(runId).report();
		return new PdfExport(pdf.render(report), filename(report.title()));
	}

	private void ensureBound(String source, int size) {
		if (size > properties.getMaxFacts()) throw ReportValidationException.tooLarge(source, "contains more than " + properties.getMaxFacts() + " facts; choose a shorter period");
	}
	private void requireView() {
		permissions.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
	}
	private CourseSummaryReportRun load(String runId) {
		try { return store.find(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId(), UUID.fromString(runId)).orElseThrow(() -> new NotFoundException("Report run not found")); }
		catch (IllegalArgumentException | NullPointerException ex) { throw new NotFoundException("Report run not found"); }
	}
	private CourseRef courseRef(CourseSummary course) { return new CourseRef(course.id(), course.name()); }
	private static String display(String name, String email) { return name == null || name.isBlank() ? (email == null ? "Unknown teacher" : email) : name.trim(); }
	private static String filename(String title) { String slug = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", ""); return (slug.isBlank() ? "course-summary-report" : slug.substring(0, Math.min(120, slug.length()))) + ".pdf"; }

}
