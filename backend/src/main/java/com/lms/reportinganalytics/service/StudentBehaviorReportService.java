package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.ReportLedgerApi;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.reportinganalytics.config.StudentBehaviorReportProperties;
import com.lms.reportinganalytics.service.pdf.StudentBehaviorPdfRenderer;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.StudentBehaviorRunRequest;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentBehaviorReportService {
    private static final String TEACHER_ROLE = "TEACHER";
    private final PermissionCheckService permissions;
    private final StudentBehaviorRequestValidator validator;
    private final CourseLookupApi courses;
    private final ReportLedgerApi ledger;
    private final PaymentStatusApi payments;
    private final StudentLookupApi students;
    private final ReportTenantInfo tenantInfo;
    private final TenantContext tenantContext;
    private final StudentBehaviorRunStore store;
    private final StudentBehaviorPdfRenderer pdf;
    private final StudentBehaviorReportProperties properties;

    public StudentBehaviorReportService(PermissionCheckService permissions, StudentBehaviorRequestValidator validator,
            CourseLookupApi courses, ReportLedgerApi ledger, PaymentStatusApi payments, StudentLookupApi students,
            ReportTenantInfo tenantInfo, TenantContext tenantContext, StudentBehaviorRunStore store,
            StudentBehaviorPdfRenderer pdf, StudentBehaviorReportProperties properties) {
        this.permissions = permissions; this.validator = validator; this.courses = courses; this.ledger = ledger;
        this.payments = payments; this.students = students; this.tenantInfo = tenantInfo; this.tenantContext = tenantContext;
        this.store = store; this.pdf = pdf; this.properties = properties;
    }

    public record Options(List<StudentBehaviorReport.CourseRef> courses) { }
    public record CategoryRows(List<StudentBehaviorRow> newStudents, List<StudentBehaviorRow> droppedStudents,
            List<StudentBehaviorRow> continuingStudents) { }

    @Transactional(readOnly = true)
    public Options options() {
        requireView();
        AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
        List<CourseSummary> available = courses.listCourseSummaries();
        Set<UUID> ids = available.stream().map(CourseSummary::id).collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> teacherByCourse = courses.getTeacherIdsByCourseId(ids);
        return new Options(available.stream()
                .filter(course -> !TEACHER_ROLE.equals(principal.role()) || principal.userId().equals(teacherByCourse.get(course.id())))
                .map(course -> new StudentBehaviorReport.CourseRef(course.id(), course.name())).toList());
    }

    @Transactional(readOnly = true)
    public StudentBehaviorReport createRun(StudentBehaviorRunRequest request) {
        requireView();
        StudentBehaviorCriteria criteria = validator.validate(request);
        requireCourseAccess(criteria.courseId());
        UUID tenantId = tenantContext.getTenantId(); UUID userId = AuthenticatedPrincipalHolder.get().userId();
        store.acquireRunSlot(tenantId, userId);
        Set<YearMonth> months = Set.of(criteria.billingMonth(), criteria.billingMonth().minusMonths(1));
        List<ReportLedgerEntry> entries;
        try { entries = ledger.findEntriesForBillingMonths(months, properties.getMaxLedgerEntries()); }
        catch (ReportScopeTooLargeException ex) { throw ReportValidationException.tooManyEntries(ex.getEntryCount(), ex.getMaxEntries()); }
        Set<UUID> orderIds = entries.stream().map(ReportLedgerEntry::orderId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<OrderLineSnapshot> lines = orderIds.isEmpty() ? List.of() : payments.findOrderLinesForOrders(orderIds);
        Set<UUID> studentIds = lines.stream().filter(line -> criteria.courseId().equals(line.courseId()))
                .map(OrderLineSnapshot::studentId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        CourseSummary course = courses.getCourseSummaries(Set.of(criteria.courseId())).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Course not found"));
        Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ZoneId zone = tenantInfo.zone(tenantId);
        StudentBehaviorCalculator.Result result = StudentBehaviorCalculator.calculate(new StudentBehaviorCalculator.Input(
                criteria, zone, generatedAt, tenantInfo.instituteName(tenantId),
                new StudentBehaviorReport.CourseRef(course.id(), course.name()), lines, entries,
                students.findUnpaidStudentProfiles(studentIds)));
        if (result.report().counts().totalRows() > properties.getMaxRows()) {
            throw ReportValidationException.tooLarge("courseId", "has more than " + properties.getMaxRows() + " classified students");
        }
        StudentBehaviorReport report = result.report().withRun(UUID.randomUUID(), generatedAt.plus(store.ttl()));
        store.save(tenantId, userId, new StudentBehaviorRun(report, result.newStudents(), result.droppedStudents(), result.continuingStudents()));
        return report;
    }

    public StudentBehaviorReport getRun(String runId) { requireView(); return load(runId).report(); }
    public CategoryRows rows(String runId) {
        requireView(); store.acquireRowsSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());
        StudentBehaviorRun run = load(runId); return new CategoryRows(run.newStudents(), run.droppedStudents(), run.continuingStudents());
    }
    public byte[] exportPdf(String runId) {
        requireView(); store.acquirePdfSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId());
        StudentBehaviorRun run = load(runId);
        if (run.report().counts().totalRows() > properties.getMaxPdfRows()) throw ReportValidationException.tooLarge("runId", "has too many rows for PDF");
        return pdf.render(run);
    }
    private void requireView() {
        AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
        if (!TEACHER_ROLE.equals(principal.role())) permissions.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
    }
    private void requireCourseAccess(UUID courseId) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
        if (!TEACHER_ROLE.equals(principal.role())) return;
        UUID teacherId = courses.getTeacherId(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
        if (!principal.userId().equals(teacherId)) throw new AccessDeniedException("You do not have permission to view this course's student behavior report");
    }
    private StudentBehaviorRun load(String runId) {
        try { return store.find(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId(), UUID.fromString(runId))
                .orElseThrow(() -> new NotFoundException("Report run not found")); }
        catch (IllegalArgumentException | NullPointerException ex) { throw new NotFoundException("Report run not found"); }
    }
}
