package com.lms.reportinganalytics.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.attendancemanagement.api.AttendanceReportQueryApi;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.reportinganalytics.config.AttendanceReportProperties;
import com.lms.reportinganalytics.service.AttendanceReport.CourseRef;
import com.lms.reportinganalytics.service.pdf.AttendancePdfRenderer;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.AttendanceRunRequest;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RPT-03 orchestration. Authoritative facts stay owned by attendance-management. */
@Service("attendanceMatrixReportService")
public class AttendanceReportService {

	private static final String TEACHER_ROLE = "TEACHER";

	private final PermissionCheckService permissionCheckService;

	private final AttendanceReportRequestValidator validator;

	private final AttendanceReportQueryApi attendanceApi;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final ReportTenantInfo tenantInfo;

	private final TenantContext tenantContext;

	private final AttendanceReportRunStore runStore;

	private final AttendancePdfRenderer pdfRenderer;

	private final AttendanceReportProperties properties;

	public AttendanceReportService(PermissionCheckService permissionCheckService,
			AttendanceReportRequestValidator validator, AttendanceReportQueryApi attendanceApi,
			CourseLookupApi courseLookupApi, StudentLookupApi studentLookupApi, ReportTenantInfo tenantInfo,
			TenantContext tenantContext, AttendanceReportRunStore runStore, AttendancePdfRenderer pdfRenderer,
			AttendanceReportProperties properties) {
		this.permissionCheckService = permissionCheckService;
		this.validator = validator;
		this.attendanceApi = attendanceApi;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.tenantInfo = tenantInfo;
		this.tenantContext = tenantContext;
		this.runStore = runStore;
		this.pdfRenderer = pdfRenderer;
		this.properties = properties;
	}

	public record Options(List<CourseRef> courses) {

	}

	@Transactional(readOnly = true)
	public Options options() {
		requireView();
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		List<CourseSummary> all = courseLookupApi.listCourseSummaries();
		Set<UUID> courseIds = all.stream().map(CourseSummary::id).collect(java.util.stream.Collectors.toSet());
		var teachers = courseLookupApi.getTeacherIdsByCourseId(courseIds);
		return new Options(all.stream()
			.filter(course -> !TEACHER_ROLE.equals(principal.role()) || principal.userId().equals(teachers.get(course.id())))
			.map(course -> new CourseRef(course.id(), course.name()))
			.toList());
	}

	@Transactional(readOnly = true)
	public AttendanceReport createRun(AttendanceRunRequest request) {
		requireView();
		AttendanceCriteria criteria = validator.validate(request);
		requireCourseAccess(criteria.courseId());
		UUID tenantId = tenantContext.getTenantId();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		runStore.acquireRunSlot(tenantId, userId);
		ZoneId zone = tenantInfo.zone(tenantId);
		Instant from = criteria.startDate().atStartOfDay(zone).toInstant();
		Instant to = criteria.endDate().plusDays(1).atStartOfDay(zone).toInstant();
		List<AttendanceReportFact> facts = attendanceApi.findReportFacts(criteria.courseId(), from, to,
				properties.getMaxFacts());
		if (facts.size() > properties.getMaxFacts()) {
			throw ReportValidationException.tooLarge("period", "contains more than " + properties.getMaxFacts()
					+ " attendance facts; choose a shorter period");
		}
		CourseSummary summary = courseLookupApi.getCourseSummaries(Set.of(criteria.courseId())).stream().findFirst()
				.orElseThrow(() -> new NotFoundException("Course not found"));
		Set<UUID> studentIds = facts.stream().map(AttendanceReportFact::studentId).collect(java.util.stream.Collectors.toSet());
		List<StudentIndexEntry> students = studentLookupApi.findStudentIndexEntries(studentIds);
		Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		AttendanceReport report = AttendanceReportCalculator.calculate(new AttendanceReportCalculator.Input(criteria, zone,
				generatedAt, tenantInfo.instituteName(tenantId), new CourseRef(summary.id(), summary.name()), facts, students));
		validateSize(report);
		report = report.withRun(UUID.randomUUID(), generatedAt.plus(runStore.ttl()));
		runStore.save(tenantId, userId, new AttendanceReportRun(report));
		return report;
	}

	public AttendanceReport getRun(String runId) {
		requireView();
		return loadRun(runId).report();
	}

	public record PdfExport(byte[] bytes, String filename) {

	}

	public PdfExport exportPdfWithFilename(String runId) {
		requireView();
		UUID tenantId = tenantContext.getTenantId();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		runStore.acquirePdfSlot(tenantId, userId);
		AttendanceReportRun run = loadRun(runId);
		if (run.report().rows().size() > properties.getMaxPdfRows()) {
			throw ReportValidationException.tooLarge("runId", "has " + run.report().rows().size()
					+ " student rows; at most " + properties.getMaxPdfRows() + " can be exported");
		}
		return new PdfExport(pdfRenderer.render(run), downloadFilename(run.report().title()));
	}

	private static String downloadFilename(String title) {
		String slug = title.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-+|-+$)", "");
		return (slug.isBlank() ? "attendance-report" : slug.substring(0, Math.min(slug.length(), 120))) + ".pdf";
	}

	private void validateSize(AttendanceReport report) {
		if (report.rows().size() > properties.getMaxStudents()) {
			throw ReportValidationException.tooLarge("period", "has " + report.rows().size() + " student rows; at most "
					+ properties.getMaxStudents() + " are allowed");
		}
		if (report.dates().size() > properties.getMaxDateColumns()) {
			throw ReportValidationException.tooLarge("period", "has " + report.dates().size() + " attendance dates; at most "
					+ properties.getMaxDateColumns() + " are allowed");
		}
		long cells = (long) report.rows().size() * report.dates().size();
		if (cells > properties.getMaxMatrixCells()) {
			throw ReportValidationException.tooLarge("period", "produces " + cells + " matrix cells; at most "
					+ properties.getMaxMatrixCells() + " are allowed");
		}
	}

	private void requireView() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TEACHER_ROLE.equals(principal.role())) {
			permissionCheckService.requirePermission(DomainArea.ATTENDANCE, PermissionAction.VIEW);
		}
	}

	private void requireCourseAccess(UUID courseId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TEACHER_ROLE.equals(principal.role())) {
			return;
		}
		UUID teacherId = courseLookupApi.getTeacherId(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
		if (!principal.userId().equals(teacherId)) {
			throw new AccessDeniedException("You do not have permission to view this course's attendance report");
		}
	}

	private AttendanceReportRun loadRun(String runId) {
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
