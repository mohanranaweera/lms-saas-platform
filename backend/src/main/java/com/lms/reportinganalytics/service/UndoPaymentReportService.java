package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.auditlogmanagement.api.PaymentRefundAuditApi;
import com.lms.auditlogmanagement.api.PaymentRefundAuditApi.RefundAuditFact;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentRefundReportApi;
import com.lms.paymentmanagement.api.PaymentRefundReportApi.RefundFact;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.reportinganalytics.config.UndoPaymentReportProperties;
import com.lms.reportinganalytics.service.pdf.UndoPaymentPdfRenderer;
import com.lms.reportinganalytics.service.support.ReportTenantInfo;
import com.lms.reportinganalytics.web.dto.UndoPaymentsRunRequest;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UndoPaymentReportService {
	public record Options(List<UndoPaymentReport.CourseRef> courses) { public Options { courses = courses == null ? List.of() : List.copyOf(courses); } }
	public record RowsPage(int page, int size, int totalRows, int totalPages, List<UndoPaymentRow> rows) { }
	private final PermissionCheckService permission;
	private final PaymentRefundReportApi refunds;
	private final PaymentStatusApi payments;
	private final PaymentRefundAuditApi audits;
	private final StudentLookupApi students;
	private final CourseLookupApi courses;
	private final ReportTenantInfo tenantInfo;
	private final TenantContext tenantContext;
	private final UndoPaymentRunStore store;
	private final UndoPaymentPdfRenderer pdf;
	private final UndoPaymentReportProperties props;
	public UndoPaymentReportService(PermissionCheckService permission, PaymentRefundReportApi refunds, PaymentStatusApi payments,
			PaymentRefundAuditApi audits, StudentLookupApi students, CourseLookupApi courses, ReportTenantInfo tenantInfo,
			TenantContext tenantContext, UndoPaymentRunStore store, UndoPaymentPdfRenderer pdf, UndoPaymentReportProperties props) {
		this.permission = permission; this.refunds = refunds; this.payments = payments; this.audits = audits; this.students = students;
		this.courses = courses; this.tenantInfo = tenantInfo; this.tenantContext = tenantContext; this.store = store; this.pdf = pdf; this.props = props;
	}
	@Transactional(readOnly = true)
	public Options options() { requireView(); return new Options(courses.listCourseSummaries().stream().map(c -> new UndoPaymentReport.CourseRef(c.id(), c.name())).toList()); }
	@Transactional(readOnly = true)
	public UndoPaymentReport createRun(UndoPaymentsRunRequest request) {
		requireView();
		if (request == null || request.startDate() == null || request.endDate() == null || request.startDate().isAfter(request.endDate()))
			throw new ReportValidationException(List.of(new FieldError("dateRange", "choose a valid date range")));
		UUID tenantId = tenantContext.getTenantId(); UUID userId = AuthenticatedPrincipalHolder.get().userId(); store.acquireRunSlot(tenantId, userId);
		ZoneId zone = tenantInfo.zone(tenantId); Instant generated = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant from = request.startDate().atStartOfDay(zone).toInstant(); Instant to = request.endDate().plusDays(1).atStartOfDay(zone).toInstant();
		List<RefundFact> facts = refunds.findEffectiveRefundsBetween(from, to, props.getMaxRows());
		Set<UUID> selected = request.courseIds() == null ? Set.of() : new HashSet<>(request.courseIds());
		Set<UUID> studentIds = facts.stream().map(RefundFact::studentId).collect(Collectors.toSet());
		Map<UUID, StudentIndexEntry> studentMap = students.findStudentIndexEntries(studentIds).stream().collect(Collectors.toMap(StudentIndexEntry::userId, x -> x, (a,b)->a));
		Set<UUID> orderIds = facts.stream().map(RefundFact::orderId).collect(Collectors.toSet());
		List<OrderLineSnapshot> lines = payments.findOrderLinesForOrders(orderIds);
		Map<UUID, List<OrderLineSnapshot>> linesByOrder = lines.stream().collect(Collectors.groupingBy(OrderLineSnapshot::orderId));
		Set<UUID> courseIds = new HashSet<>(selected); lines.forEach(l -> courseIds.add(l.courseId()));
		facts.stream().map(RefundFact::legacyCourseId).filter(java.util.Objects::nonNull).forEach(courseIds::add);
		Map<UUID, String> names = courses.getCourseSummaries(courseIds).stream().collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a,b)->a));
		Map<UUID, RefundAuditFact> audit = audits.findPaymentRefundAudits(facts.stream().map(RefundFact::refundId).toList()).stream().collect(Collectors.toMap(RefundAuditFact::refundId, x -> x, (a,b)->a));
		List<UndoPaymentRow> rows = new ArrayList<>();
		for (RefundFact fact : facts) {
			List<OrderLineSnapshot> orderLines = linesByOrder.getOrDefault(fact.orderId(), List.of());
			if (orderLines.isEmpty() && fact.legacyCourseId() != null) {
				if (!selected.isEmpty() && !selected.contains(fact.legacyCourseId())) continue;
				StudentIndexEntry student = studentMap.get(fact.studentId()); RefundAuditFact actor = audit.get(fact.refundId());
				rows.add(new UndoPaymentRow(fact.refundId(), fact.studentId(), student == null ? "Unknown student" : student.name(),
						student == null ? "—" : student.indexNumber(), fact.legacyCourseId(), names.getOrDefault(fact.legacyCourseId(), "Unknown course"),
						actor == null || actor.actorDisplayName() == null ? ("CHARGEBACK".equals(fact.source()) ? "System" : "Unknown actor") : actor.actorDisplayName(),
						fact.refundedAt(), fact.amount(), fact.paymentCreatedAt(), fact.reason() == null || fact.reason().isBlank() ? "—" : fact.reason()));
				continue;
			}
			if (orderLines.isEmpty()) {
				continue;
			}
			BigDecimal total = orderLines.stream().map(OrderLineSnapshot::courseShare).reduce(BigDecimal.ZERO, BigDecimal::add);
			for (OrderLineSnapshot line : orderLines) {
				if (!selected.isEmpty() && !selected.contains(line.courseId())) continue;
				if (total.signum() <= 0) continue;
				BigDecimal amount = fact.amount().multiply(line.courseShare()).divide(total, 2, java.math.RoundingMode.HALF_UP);
				if (amount.signum() == 0) continue;
				StudentIndexEntry student = studentMap.get(fact.studentId()); RefundAuditFact actor = audit.get(fact.refundId());
				rows.add(new UndoPaymentRow(fact.refundId(), fact.studentId(), student == null ? "Unknown student" : student.name(),
						student == null ? "—" : student.indexNumber(), line.courseId(), names.getOrDefault(line.courseId(), "Unknown course"),
						actor == null || actor.actorDisplayName() == null ? ("CHARGEBACK".equals(fact.source()) ? "System" : "Unknown actor") : actor.actorDisplayName(),
						fact.refundedAt(), amount, fact.paymentCreatedAt(), fact.reason() == null || fact.reason().isBlank() ? "—" : fact.reason()));
			}
		}
		rows.sort(java.util.Comparator.comparing(UndoPaymentRow::undoDate).reversed().thenComparing(UndoPaymentRow::refundId).thenComparing(UndoPaymentRow::courseId));
		if (rows.size() > props.getMaxRows()) throw ReportValidationException.tooLarge("dateRange", "contains too many rows");
		Map<UUID, UndoPaymentReport.CourseRef> selectedCourses = names.entrySet().stream().filter(e -> selected.contains(e.getKey())).collect(Collectors.toMap(Map.Entry::getKey, e -> new UndoPaymentReport.CourseRef(e.getKey(), e.getValue())));
		UndoPaymentReport report = new UndoPaymentReport(UUID.randomUUID(), generated.plus(java.time.Duration.ofMinutes(15)), generated, zone.getId(),
				new UndoPaymentReport.Institute(tenantInfo.instituteName(tenantId)), new UndoPaymentReport.Filters(selectedCourses.values().stream().toList(), request.startDate(), request.endDate(), (int)(request.endDate().toEpochDay()-request.startDate().toEpochDay()+1)),
				rows.size(), (int) rows.stream().map(UndoPaymentRow::refundId).distinct().count(), rows.stream().map(UndoPaymentRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
		store.save(tenantId, userId, new UndoPaymentRun(report, rows)); return report;
	}
	public UndoPaymentReport getRun(String id) { requireView(); return load(id).report(); }
	public RowsPage rows(String id, String page, String size) { requireView(); int p=parse(page,0), s=parse(size,50); if(p<0||s<1||s>200) throw new ReportValidationException(List.of(new FieldError("page", "must be valid"))); store.acquireRowsSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId()); List<UndoPaymentRow> all=load(id).rows(); int pages=all.isEmpty()?0:(all.size()+s-1)/s; int from=Math.min(all.size(), p*s); return new RowsPage(p,s,all.size(),pages,all.subList(from,Math.min(all.size(),from+s))); }
	public byte[] exportPdf(String id) { requireView(); store.acquirePdfSlot(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId()); UndoPaymentRun run=load(id); if(run.rows().size()>props.getMaxPdfRows()) throw ReportValidationException.tooLarge("runId", "has too many rows for PDF"); return pdf.render(run); }
	private UndoPaymentRun load(String id) { try { return store.find(tenantContext.getTenantId(), AuthenticatedPrincipalHolder.get().userId(), UUID.fromString(id)).orElseThrow(() -> new NotFoundException("Report run not found")); } catch (IllegalArgumentException ex) { throw new NotFoundException("Report run not found"); } }
	private void requireView() { permission.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW); }
	private static int parse(String value,int fallback){ if(value==null||value.isBlank())return fallback; try{return Integer.parseInt(value);}catch(NumberFormatException e){return -1;} }
}
