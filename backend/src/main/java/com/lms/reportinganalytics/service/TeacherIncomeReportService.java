package com.lms.reportinganalytics.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi;
import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherRef;
import com.lms.reportinganalytics.service.pdf.TeacherIncomePdfRenderer;
import com.lms.reportinganalytics.web.dto.TeacherIncomeRunRequest;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
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

/**
 * RPT-01 Teacher Income Summary - orchestration only (rules live in {@link
 * TeacherIncomeCalculator}). Every operation, including both PDF exports,
 * requires {@code FINANCE_EXPENSES}/{@code VIEW}; the tenant always comes from
 * the resolved {@link TenantContext} and the run owner from the authenticated
 * principal - never from the request. Strictly read-only: it never writes a
 * payment, ledger, settlement or enrollment row (read-only transactions; the
 * only write is the ephemeral Redis run snapshot). Exports read only the stored
 * run of the same tenant and user and never recompute.
 */
@Service
public class TeacherIncomeReportService {

	public static final List<String> PAYMENT_METHODS = List.of("GATEWAY", "MANUAL_SLIP", "MANUAL_RECEIPT");

	private final PermissionCheckService permissionCheckService;

	private final TeacherIncomeRequestValidator validator;

	private final TeacherIncomeLedgerApi ledgerApi;

	private final CourseLookupApi courseLookupApi;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	private final TeacherIncomeRunStore runStore;

	private final TeacherIncomePdfRenderer pdfRenderer;

	private final TeacherIncomeRunRateLimiter rateLimiter;

	private final TeacherIncomeReportProperties properties;

	public TeacherIncomeReportService(PermissionCheckService permissionCheckService,
			TeacherIncomeRequestValidator validator, TeacherIncomeLedgerApi ledgerApi, CourseLookupApi courseLookupApi,
			TeacherLookupApi teacherLookupApi, UserProvisioningApi userProvisioningApi,
			TenantConfigApi tenantConfigApi, TenantLookupApi tenantLookupApi, TenantContext tenantContext,
			TeacherIncomeRunStore runStore, TeacherIncomePdfRenderer pdfRenderer,
			TeacherIncomeRunRateLimiter rateLimiter, TeacherIncomeReportProperties properties) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.permissionCheckService = permissionCheckService;
		this.validator = validator;
		this.ledgerApi = ledgerApi;
		this.courseLookupApi = courseLookupApi;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
		this.runStore = runStore;
		this.pdfRenderer = pdfRenderer;
	}

	public record Options(List<TeacherRef> teachers, List<String> paymentMethods) {

	}

	@Transactional(readOnly = true)
	public Options options() {
		requireView();
		List<TeacherRef> teachers = teacherLookupApi.listTeachers()
			.stream()
			.map(t -> new TeacherRef(t.userId(), displayName(t.name(), t.email())))
			.toList();
		return new Options(teachers, PAYMENT_METHODS);
	}

	@Transactional(readOnly = true)
	public TeacherIncomeReport createRun(TeacherIncomeRunRequest request) {
		requireView();
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		UUID tenantId = tenantContext.getTenantId();
		rateLimiter.acquire(tenantId, userId);
		TeacherIncomeCriteria criteria = validator.validate(request);
		try {
			return computeAndStore(tenantId, userId, criteria);
		}
		catch (ReportScopeTooLargeException ex) {
			throw ReportValidationException.tooManyEntries(ex.getEntryCount(), ex.getMaxEntries());
		}
	}

	private TeacherIncomeReport computeAndStore(UUID tenantId, UUID userId, TeacherIncomeCriteria criteria) {
		int maxEntries = properties.getMaxLedgerEntries();
		ZoneId zone = tenantZone(tenantId);
		Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		List<ReportLedgerEntry> entries;
		List<ReportLedgerEntry> monthWindowEntries = List.of();
		List<InstantRange> windows;
		if (criteria.periodMode() == PeriodMode.DATES) {
			windows = List.of(new InstantRange(criteria.startDate().atStartOfDay(zone).toInstant(),
					criteria.endDate().plusDays(1).atStartOfDay(zone).toInstant()));
			entries = ledgerApi.findEntriesCreatedIn(windows, maxEntries);
		}
		else {
			windows = monthWindows(criteria, zone);
			entries = ledgerApi.findEntriesForBillingMonths(criteria.months(), maxEntries);
			monthWindowEntries = ledgerApi.findEntriesCreatedIn(windows, maxEntries);
		}

		Set<UUID> courseIds = new HashSet<>();
		for (List<ReportLedgerEntry> list : List.of(entries, monthWindowEntries)) {
			for (ReportLedgerEntry entry : list) {
				for (ReportCourseAllocation allocation : entry.courses()) {
					courseIds.add(allocation.courseId());
				}
			}
		}
		Map<UUID, UUID> teacherByCourse = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getTeacherIdsByCourseId(courseIds);
		Map<UUID, String> courseNames = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getCourseSummaries(courseIds)
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));

		TeacherIncomeReport computed = TeacherIncomeCalculator.calculate(new TeacherIncomeCalculator.Input(criteria,
				zone, generatedAt, instituteName(tenantId), teacherRefs(criteria.teacherIds()), entries,
				monthWindowEntries, teacherByCourse, courseNames, ledgerApi.findShareRates(criteria.teacherIds()),
				ledgerApi.findPaidSettlements(criteria.teacherIds(), windows),
				tenantConfigApi.resolveDefaultCurrency(tenantId)));
		int rows = computed.sections().stream().flatMap(s -> s.teachers().stream()).mapToInt(t -> t.courses().size())
			.sum();
		if (rows > properties.getMaxCourseRows()) {
			throw ReportValidationException.tooLarge(rows, properties.getMaxCourseRows());
		}
		TeacherIncomeReport run = computed.withRun(UUID.randomUUID(), generatedAt.plus(TeacherIncomeRunStore.TTL));
		runStore.save(tenantId, userId, run);
		return run;
	}

	public TeacherIncomeReport getRun(String runId) {
		requireView();
		return loadRun(runId);
	}

	public byte[] exportSummaryPdf(String runId) {
		requireView();
		return pdfRenderer.renderSummary(loadRun(runId));
	}

	/** Informational payslip of one teacher of the run - generating it writes nothing. */
	public byte[] exportPayslipPdf(String runId, UUID teacherId) {
		requireView();
		return pdfRenderer.renderPayslip(loadRun(runId), teacherId);
	}

	// ------------------------------------------------------------------

	private void requireView() {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
	}

	private TeacherIncomeReport loadRun(String runId) {
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

	/** One window per selected month in the tenant timezone, adjacent months merged. */
	static List<InstantRange> monthWindows(TeacherIncomeCriteria criteria, ZoneId zone) {
		List<InstantRange> windows = new ArrayList<>();
		YearMonth runStart = null;
		YearMonth previous = null;
		for (YearMonth month : criteria.months()) {
			if (runStart == null) {
				runStart = month;
			}
			else if (!month.equals(previous.plusMonths(1))) {
				windows.add(window(runStart, previous, zone));
				runStart = month;
			}
			previous = month;
		}
		if (runStart != null) {
			windows.add(window(runStart, previous, zone));
		}
		return windows;
	}

	private static InstantRange window(YearMonth first, YearMonth last, ZoneId zone) {
		return new InstantRange(first.atDay(1).atStartOfDay(zone).toInstant(),
				last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
	}

	private List<TeacherRef> teacherRefs(List<UUID> teacherIds) {
		Map<UUID, String> names = new HashMap<>();
		teacherLookupApi.listTeachers().forEach(t -> names.put(t.userId(), t.name()));
		Map<UUID, String> emails = userProvisioningApi.findTenantUserSummaries(teacherIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
		return teacherIds.stream().map(id -> new TeacherRef(id, displayName(names.get(id), emails.get(id)))).toList();
	}

	private static String displayName(String name, String email) {
		if (name != null && !name.isBlank()) {
			return name.trim();
		}
		return email == null ? "Unknown teacher" : email;
	}

	private String instituteName(UUID tenantId) {
		return tenantLookupApi.resolveTenantSummaries(Set.of(tenantId))
			.stream()
			.findFirst()
			.map(TenantSummary::name)
			.orElse("");
	}

	private ZoneId tenantZone(UUID tenantId) {
		Object configured = tenantConfigApi.resolveValue(tenantId, ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zoneName) {
			try {
				return ZoneId.of(zoneName);
			}
			catch (DateTimeException ignored) {
				// Registry-validated; UTC fallback is defensive only.
			}
		}
		return ZoneId.of("UTC");
	}

}
