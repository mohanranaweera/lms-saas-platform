package com.lms.usermanagement.student.service;

import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentDirectoryRow;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STU-MGMT-02 student directory (spec sections 5 and 9): server-side search and
 * pagination over the whole tenant, one row per student (a single {@code
 * student_profile} query - no joins that could duplicate rows), sorted by index number,
 * removed students excluded. Email search goes through identity's tenant-scoped email
 * lookup; last login is one batched {@code device_session} aggregate per page.
 */
@Service
@Transactional(readOnly = true)
public class StudentDirectoryService {

	public enum Tab {
		ACTIVE, PENDING
	}

	public enum Field {
		ALL, INDEX, FIRST_NAME, LAST_NAME, EMAIL, MOBILE
	}

	public static final int MAX_PAGE_SIZE = 100;

	/** Upper bound of email matches considered for one search (identity lookup cap). */
	static final int EMAIL_MATCH_LIMIT = 5000;

	private static final int EXPORT_BATCH = 500;

	private static final DateTimeFormatter LAST_LOGIN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",
			Locale.ENGLISH);

	private final StudentProfileRepository studentProfileRepository;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	private final StudentManagementService studentManagementService;

	private final com.lms.auditlogmanagement.api.AuditLogApi auditLogApi;

	public StudentDirectoryService(StudentProfileRepository studentProfileRepository,
			UserProvisioningApi userProvisioningApi, PermissionCheckService permissionCheckService,
			StudentManagementService studentManagementService,
			com.lms.auditlogmanagement.api.AuditLogApi auditLogApi) {
		this.auditLogApi = auditLogApi;
		this.studentProfileRepository = studentProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
		this.studentManagementService = studentManagementService;
	}

	public PageResponse<StudentDirectoryRow> directory(Tab tab, Field field, String q, int page, int size) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		requirePendingTabAccess(tab);
		int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		Page<StudentProfile> profiles = studentProfileRepository.findAll(spec(tab, field, q),
				PageRequest.of(Math.max(page, 0), boundedSize));
		List<StudentDirectoryRow> rows = toRows(profiles.getContent());
		return new PageResponse<>(rows, profiles.getNumber(), profiles.getSize(), profiles.getTotalElements(),
				profiles.getTotalPages());
	}

	/**
	 * The whole filtered scope as CSV (UTF-8 with BOM). Cells starting with {@code = + -
	 * @ TAB CR} are prefixed with {@code '} (spreadsheet formula injection). No NIC,
	 * guardian or credential columns.
	 */
	@org.springframework.transaction.annotation.Transactional
	public byte[] exportCsv(Tab tab, Field field, String q) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		requirePendingTabAccess(tab);
		ZoneId zone = studentManagementService.tenantZone();
		StringBuilder csv = new StringBuilder("﻿");
		csv.append("Index Number,First Name,Last Name,Email,Mobile,Last Login,Status\r\n");
		Specification<StudentProfile> spec = spec(tab, field, q);
		int page = 0;
		Page<StudentProfile> batch;
		do {
			batch = studentProfileRepository.findAll(spec, PageRequest.of(page++, EXPORT_BATCH));
			for (StudentDirectoryRow row : toRows(batch.getContent())) {
				csv.append(cell(row.indexNumber())).append(',')
					.append(cell(row.firstName())).append(',')
					.append(cell(row.lastName())).append(',')
					.append(cell(row.email())).append(',')
					.append(cell(row.mobile())).append(',')
					.append(cell(row.lastLoginAt() == null ? null : LAST_LOGIN_FORMAT.format(row.lastLoginAt().atZone(zone))))
					.append(',')
					.append(cell(row.status()))
					.append("\r\n");
			}
		}
		while (batch.hasNext());
		// Bulk PII export is a security-relevant action: record who exported which scope.
		var principal = com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder.get();
		java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
		metadata.put("tab", String.valueOf(tab));
		metadata.put("field", String.valueOf(field));
		metadata.put("query", q == null ? "" : q);
		metadata.put("rows", batch.getTotalElements());
		auditLogApi.record(new com.lms.auditlogmanagement.api.AuditLogEntry(principal.userId(),
				"student.directory_exported", "tenant", principal.tenantId(), null, metadata));
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Formula-injection-safe, RFC 4180-quoted CSV cell. */
	static String cell(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String safe = value;
		char first = safe.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
			safe = "'" + safe;
		}
		if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
			safe = '"' + safe.replace("\"", "\"\"") + '"';
		}
		return safe;
	}

	private List<StudentDirectoryRow> toRows(List<StudentProfile> profiles) {
		if (profiles.isEmpty()) {
			return List.of();
		}
		List<UUID> userIds = profiles.stream().map(StudentProfile::getUserId).toList();
		Map<UUID, TenantUserSummary> summaries = userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, Function.identity()));
		Map<UUID, Instant> lastLogins = userProvisioningApi.findLastLoginAt(userIds);
		List<StudentDirectoryRow> rows = new ArrayList<>(profiles.size());
		for (StudentProfile profile : profiles) {
			TenantUserSummary summary = summaries.get(profile.getUserId());
			rows.add(new StudentDirectoryRow(profile.getId(), profile.getIndexNumber(), profile.getFirstName(),
					profile.getLastName(), summary == null ? null : summary.email(), profile.getMobile(),
					lastLogins.get(profile.getUserId()), summary == null ? null : summary.status(),
					profile.getProfileMediaFileId() != null));
		}
		return rows;
	}

	private Specification<StudentProfile> spec(Tab tab, Field field, String q) {
		Tab effectiveTab = tab == null ? Tab.ACTIVE : tab;
		Field effectiveField = field == null ? Field.ALL : field;
		String term = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
		Set<UUID> emailUserIds = term.isEmpty() || !(effectiveField == Field.ALL || effectiveField == Field.EMAIL)
				? Set.of()
				: Set.copyOf(userProvisioningApi.findTenantUserIdsByEmailContaining(term, EMAIL_MATCH_LIMIT));
		String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.isNull(root.get("removedAt")));
			predicates.add(cb.equal(root.get("pendingApproval"), effectiveTab == Tab.PENDING));
			if (!term.isEmpty()) {
				Expression<String> index = cb.lower(cb.coalesce(root.get("indexNumber"), ""));
				Expression<String> first = cb.lower(cb.coalesce(root.get("firstName"), root.get("name")));
				Expression<String> last = cb.lower(cb.coalesce(root.get("lastName"), ""));
				Expression<String> mobile = cb.lower(cb.coalesce(root.get("mobile"), ""));
				Predicate byIndex = cb.like(index, pattern, '\\');
				Predicate byFirst = cb.like(first, pattern, '\\');
				Predicate byLast = cb.like(last, pattern, '\\');
				Predicate byMobile = cb.like(mobile, pattern, '\\');
				Predicate byEmail = emailUserIds.isEmpty() ? cb.disjunction() : root.get("userId").in(emailUserIds);
				predicates.add(switch (effectiveField) {
					case INDEX -> byIndex;
					case FIRST_NAME -> byFirst;
					case LAST_NAME -> byLast;
					case EMAIL -> byEmail;
					case MOBILE -> byMobile;
					case ALL -> cb.or(byIndex, byFirst, byLast, byMobile, byEmail);
				});
			}
			if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
				// Index numbers are zero-padded to 4 digits and grow beyond: order by length, then value.
				query.orderBy(cb.asc(cb.length(root.get("indexNumber"))), cb.asc(root.get("indexNumber")),
						cb.asc(root.get("id")));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/**
	 * STAFF-MGMT-03 §6.2: the PENDING tab (students awaiting approval) needs View Pending
	 * Students; static roles hold it exactly when they hold STUDENTS/VIEW.
	 */
	private void requirePendingTabAccess(Tab tab) {
		if (tab == Tab.PENDING) {
			permissionCheckService.requireCapability(Capability.STUDENTS_VIEW_PENDING);
		}
	}

}
