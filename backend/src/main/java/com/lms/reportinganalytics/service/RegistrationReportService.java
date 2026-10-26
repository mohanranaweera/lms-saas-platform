package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.enrollmentmanagement.api.RegistrationReportEnrollmentApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.reportinganalytics.config.RegistrationReportProperties;
import com.lms.reportinganalytics.service.pdf.RegistrationPdfRenderer;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.RegistrationRunRequest;
import com.lms.usermanagement.api.RegistrationReportStudent;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only orchestration for tenant student registrations and immutable run snapshots. */
@Service
public class RegistrationReportService {
	public static final int MAX_PAGE_SIZE = 200;
	public static final int DEFAULT_PAGE_SIZE = 50;
	private static final int MAX_SOURCE_ROWS = 20_000;
	private final PermissionCheckService permissionCheckService;
	private final RegistrationRequestValidator validator;
	private final StudentLookupApi studentLookupApi;
	private final RegistrationReportEnrollmentApi enrollmentApi;
	private final CourseLookupApi courseLookupApi;
	private final UserProvisioningApi userProvisioningApi;
	private final ReportTenantInfo tenantInfo;
	private final TenantContext tenantContext;
	private final RegistrationRunStore runStore;
	private final RegistrationPdfRenderer pdfRenderer;
	private final RegistrationReportProperties properties;

	public RegistrationReportService(PermissionCheckService permissionCheckService, RegistrationRequestValidator validator,
			StudentLookupApi studentLookupApi, RegistrationReportEnrollmentApi enrollmentApi, CourseLookupApi courseLookupApi,
			UserProvisioningApi userProvisioningApi, ReportTenantInfo tenantInfo, TenantContext tenantContext,
			RegistrationRunStore runStore, RegistrationPdfRenderer pdfRenderer, RegistrationReportProperties properties) {
		this.permissionCheckService = permissionCheckService; this.validator = validator; this.studentLookupApi = studentLookupApi;
		this.enrollmentApi = enrollmentApi; this.courseLookupApi = courseLookupApi; this.userProvisioningApi = userProvisioningApi;
		this.tenantInfo = tenantInfo; this.tenantContext = tenantContext; this.runStore = runStore; this.pdfRenderer = pdfRenderer; this.properties = properties;
	}

	public record Options(List<RegistrationReport.CourseRef> courses, List<RegistrationReport.OperatorRef> operators,
			List<String> registrationMethods) {
		public Options { courses = courses == null ? List.of() : List.copyOf(courses); operators = operators == null ? List.of() : List.copyOf(operators); registrationMethods = registrationMethods == null ? List.of() : List.copyOf(registrationMethods); }
	}
	public record RowsPage(int page, int size, int totalRows, int totalPages, List<RegistrationRow> rows) { }

	@Transactional(readOnly = true)
	public Options options() {
		requireView();
		List<RegistrationReport.CourseRef> courses = courseLookupApi.listCourseSummaries().stream().map(c -> new RegistrationReport.CourseRef(c.id(), c.name())).toList();
		List<UUID> operatorIds = studentLookupApi.findRegistrationOperatorIds();
		Map<UUID, TenantUserSummary> operators = operatorIds.isEmpty() ? Map.of() : userProvisioningApi.findTenantUserSummaries(operatorIds).stream().collect(Collectors.toMap(TenantUserSummary::userId, Function.identity(), (a, b) -> a));
		List<RegistrationReport.OperatorRef> operatorRefs = operatorIds.stream().sorted().map(id -> new RegistrationReport.OperatorRef(id, operators.get(id) == null ? id.toString() : operators.get(id).email())).toList();
		return new Options(courses, operatorRefs, List.of("SELF_REGISTERED", "ADMIN_CREATED"));
	}

	@Transactional(readOnly = true)
	public RegistrationReport createRun(RegistrationRunRequest request) {
		requireView();
		RegistrationCriteria criteria = validator.validate(request);
		UUID tenantId = tenantContext.getTenantId(); UUID userId = AuthenticatedPrincipalHolder.get().userId();
		runStore.acquireRunSlot(tenantId, userId);
		ZoneId zone = tenantInfo.zone(tenantId);
		Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant from = criteria.startDate().atStartOfDay(zone).toInstant();
		Instant to = criteria.endDate().plusDays(1).atStartOfDay(zone).toInstant();
		List<RegistrationReportStudent> source = studentLookupApi.findRegistrationsBetween(from, to, MAX_SOURCE_ROWS);
		if (source.size() > MAX_SOURCE_ROWS) throw ReportValidationException.tooLarge("date range", "contains more than " + MAX_SOURCE_ROWS + " registrations");
		Map<UUID, Set<UUID>> coursesByStudent = enrollmentApi.findCurrentCourseIdsByStudentIds(source.stream().map(RegistrationReportStudent::userId).toList(), MAX_SOURCE_ROWS);
		Set<UUID> courseIds = source.stream().flatMap(s -> coursesByStudent.getOrDefault(s.userId(), Set.of()).stream()).collect(Collectors.toCollection(LinkedHashSet::new));
		courseIds.addAll(criteria.courseIds());
		Map<UUID, String> courseNames = courseIds.isEmpty() ? Map.of() : courseLookupApi.getCourseSummaries(courseIds).stream().collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a,b) -> a));
		List<UUID> availableOperatorIds = studentLookupApi.findRegistrationOperatorIds();
		if (criteria.operatorId() != null && !availableOperatorIds.contains(criteria.operatorId())) {
			throw new ReportValidationException(List.of(new FieldError("operatorId", "operator not found")));
		}
		Set<UUID> operatorIds = source.stream().map(RegistrationReportStudent::registeredBy).filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		if (criteria.operatorId() != null) operatorIds.add(criteria.operatorId());
		Map<UUID, TenantUserSummary> operators = operatorIds.isEmpty() ? Map.of() : userProvisioningApi.findTenantUserSummaries(operatorIds).stream().collect(Collectors.toMap(TenantUserSummary::userId, Function.identity(), (a,b) -> a));
		List<RegistrationRow> rows = new ArrayList<>();
		for (RegistrationReportStudent student : source) {
			if (criteria.operatorId() != null && !criteria.operatorId().equals(student.registeredBy())) continue;
			if (criteria.registrationMethod() != null && !criteria.registrationMethod().equals(student.registrationMethod())) continue;
			Set<UUID> associated = coursesByStudent.getOrDefault(student.userId(), Set.of());
			if (!criteria.courseIds().isEmpty() && criteria.courseIds().stream().noneMatch(associated::contains)) continue;
			List<RegistrationReport.CourseRef> refs = associated.stream().sorted().map(id -> new RegistrationReport.CourseRef(id, courseNames.getOrDefault(id, "Unknown course"))).toList();
			TenantUserSummary operator = student.registeredBy() == null ? null : operators.get(student.registeredBy());
			rows.add(new RegistrationRow(student.studentProfileId(), student.userId(), dash(student.indexNumber()), dash(student.name()), dash(student.email()), dash(student.mobile()), student.registeredBy(), operator == null ? null : operator.email(), student.registrationMethod(), refs, null, student.registeredAt()));
		}
		RegistrationReport.OperatorRef operatorRef = null;
		if (criteria.operatorId() != null) {
			TenantUserSummary selectedOperator = operators.get(criteria.operatorId());
			if (selectedOperator != null) operatorRef = new RegistrationReport.OperatorRef(criteria.operatorId(), selectedOperator.email());
		}
		RegistrationReport.Filters filters = new RegistrationReport.Filters(criteria.courseIds().stream().map(id -> new RegistrationReport.CourseRef(id, courseNames.getOrDefault(id, "Unknown course"))).toList(), operatorRef, criteria.registrationMethod(), criteria.startDate(), criteria.endDate(), (int) (criteria.endDate().toEpochDay() - criteria.startDate().toEpochDay() + 1));
		RegistrationReport report = new RegistrationReport(UUID.randomUUID(), generatedAt.plus(runStore.ttl()), generatedAt, zone.getId(), new RegistrationReport.Institute(tenantInfo.instituteName(tenantId)), filters, new RegistrationReport.CurrentFacts("current course_participation as of run generation", "latest confirmed billing period; unavailable where payment facts cannot be mapped", generatedAt), rows.size());
		RegistrationRun run = new RegistrationRun(report, rows);
		if (rows.size() > properties.getMaxRows()) throw ReportValidationException.tooLarge("date range", "has " + rows.size() + " rows; at most " + properties.getMaxRows() + " are allowed");
		runStore.save(tenantId, userId, run);
		return report;
	}

	public RegistrationReport getRun(String runId) { requireView(); return loadRun(runId).report(); }
	public RowsPage rows(String runId, String page, String size) {
		requireView(); int p = parseInt(page, 0), s = parseInt(size, DEFAULT_PAGE_SIZE);
		if (p < 0 || s < 1 || s > MAX_PAGE_SIZE) throw new ReportValidationException(List.of(new FieldError("page", "must be valid")));
		runStore.acquireRowsSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());
		List<RegistrationRow> rows = loadRun(runId).rows(); int total = rows.size(); int pages = total == 0 ? 0 : (int) ((total + (long)s - 1) / s); int from = Math.min(total, p * s); int to = Math.min(total, from + s);
		return new RowsPage(p, s, total, pages, rows.subList(from, to));
	}
	public byte[] exportPdf(String runId) {
		requireView(); runStore.acquirePdfSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId()); RegistrationRun run = loadRun(runId);
		if (run.rows().size() > properties.getMaxPdfRows()) throw ReportValidationException.tooLarge("runId", "has too many rows for PDF");
		return pdfRenderer.render(run);
	}
	private RegistrationRun loadRun(String runId) { UUID id; try { id = UUID.fromString(runId); } catch (IllegalArgumentException | NullPointerException ex) { throw new NotFoundException("Report run not found"); } return runStore.find(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId(), id).orElseThrow(() -> new NotFoundException("Report run not found")); }
	private void requireView() { permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW); }
	private static int parseInt(String value, int fallback) { if (value == null || value.isBlank()) return fallback; try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ex) { return -1; } }
	private static String dash(String value) { return value == null || value.isBlank() ? "—" : value; }
}
