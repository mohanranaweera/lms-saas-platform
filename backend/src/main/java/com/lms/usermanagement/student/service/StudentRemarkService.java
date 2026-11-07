package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.domain.StudentRemark;
import com.lms.usermanagement.student.repository.StudentRemarkRepository;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.RemarkResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STU-MGMT-02 staff-only remarks. {@code STUDENTS} VIEW to list, CREATE_EDIT to add;
 * students and teachers hold neither, and no {@code /me} or public path exposes remarks.
 * Bodies are stored as plain text exactly as typed (trimmed) - clients render them as
 * text, never as HTML. Remarks are immutable.
 */
@Service
@Transactional
public class StudentRemarkService {

	private final StudentRemarkRepository remarkRepository;

	private final StudentManagementService studentManagementService;

	private final PermissionCheckService permissionCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public StudentRemarkService(StudentRemarkRepository remarkRepository,
			StudentManagementService studentManagementService, PermissionCheckService permissionCheckService,
			UserProvisioningApi userProvisioningApi, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.remarkRepository = remarkRepository;
		this.studentManagementService = studentManagementService;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	/** Newest first; optional {@code [from, to)} filter on the creation time. */
	@Transactional(readOnly = true)
	public PageResponse<RemarkResponse> list(UUID studentId, Instant from, Instant to, int page, int size) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		StudentProfile profile = studentManagementService.requireManagedStudent(studentId);
		Page<StudentRemark> remarks = remarkRepository.findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("studentProfileId"), profile.getId()));
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.get("createdAt"), to));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
		Map<UUID, String> authors = authorNames(
				remarks.getContent().stream().map(StudentRemark::getAuthorUserId).collect(Collectors.toSet()));
		List<RemarkResponse> content = remarks.getContent()
			.stream()
			.map(remark -> new RemarkResponse(remark.getId(), remark.getBody(), authors.get(remark.getAuthorUserId()),
					remark.getCreatedAt()))
			.toList();
		return new PageResponse<>(content, remarks.getNumber(), remarks.getSize(), remarks.getTotalElements(),
				remarks.getTotalPages());
	}

	public RemarkResponse add(UUID studentId, String body) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		StudentProfile profile = studentManagementService.requireManagedStudent(studentId);
		String text = body == null ? "" : body.strip();
		if (text.isEmpty() || text.length() > StudentRemark.MAX_BODY_LENGTH) {
			throw new FieldValidationException("The remark is not valid",
					List.of(new FieldError("body", "A remark must be 1-2000 characters")));
		}
		UUID authorId = AuthenticatedPrincipalHolder.get().userId();
		StudentRemark remark = remarkRepository.saveAndFlush(
				new StudentRemark(tenantContext.getTenantId(), profile.getId(), text, authorId, Instant.now()));
		auditLogApi.record(new AuditLogEntry(authorId, "student.remark_added", StudentManagementService.TARGET,
				profile.getId(), null, Map.of("remarkId", remark.getId().toString())));
		return new RemarkResponse(remark.getId(), remark.getBody(), authorNames(Set.of(authorId)).get(authorId),
				remark.getCreatedAt());
	}

	private Map<UUID, String> authorNames(Set<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email));
	}

}
