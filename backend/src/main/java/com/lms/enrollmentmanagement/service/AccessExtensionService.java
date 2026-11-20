package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.EnrollmentAccessExtension;
import com.lms.enrollmentmanagement.repository.EnrollmentAccessExtensionRepository;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual access extensions (Wave 10, product-owner decision D1, ADR-022). A
 * Tenant Admin ({@code ACCESS_EXPIRY} C/E) may extend the CURRENT enrollment
 * of a student - whether still active or already expired - but never a
 * revoked/superseded one, and never a lifetime one. Extensions are new
 * append-only rows; {@code enrollment.access_expires_at}, payments and ledger
 * are never touched, and no enrollment is created, so this is not a new
 * activation path (paid reactivation, ADR-013, is unchanged).
 */
@Service
public class AccessExtensionService {

	public record AccessExtensionView(UUID id, UUID enrollmentId, Instant previousExpiresAt, Instant newExpiresAt,
			String reason, UUID grantedBy, Instant grantedAt) {
	}

	private static final Duration MAX_HORIZON = Duration.ofDays(5 * 366);

	private static final int MAX_REASON_LENGTH = 500;

	private final EnrollmentRepository enrollmentRepository;

	private final EnrollmentAccessExtensionRepository extensionRepository;

	private final AccessWindowCalculator accessWindowCalculator;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final StudentLookupApi studentLookupApi;

	private final TenantContext tenantContext;

	public AccessExtensionService(EnrollmentRepository enrollmentRepository,
			EnrollmentAccessExtensionRepository extensionRepository, AccessWindowCalculator accessWindowCalculator,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, StudentLookupApi studentLookupApi,
			TenantContext tenantContext) {
		this.enrollmentRepository = enrollmentRepository;
		this.extensionRepository = extensionRepository;
		this.accessWindowCalculator = accessWindowCalculator;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.studentLookupApi = studentLookupApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public List<AccessExtensionView> listExtensions(UUID enrollmentId) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW);
		requireEnrollment(enrollmentId);
		return extensionRepository.findByEnrollmentIdNewestFirst(enrollmentId)
			.stream()
			.map(AccessExtensionService::toView)
			.toList();
	}

	@Transactional
	public AccessExtensionView extend(UUID enrollmentId, Instant newExpiresAt, String reason) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.CREATE_EDIT);
		String checkedReason = requireReason(reason);
		Enrollment enrollment = requireEnrollment(enrollmentId);
		if (enrollment.getSupersededAt() != null) {
			throw new ConflictException("This enrollment was revoked or replaced and cannot be extended");
		}
		if (enrollment.getAccessExpiresAt() == null) {
			throw new ConflictException("This enrollment has lifetime access - there is nothing to extend");
		}
		Instant now = Instant.now();
		AccessWindowCalculator.AccessWindow window = accessWindowCalculator.windowFor(enrollment);
		Instant previous = window.effectiveExpiresAt();
		if (newExpiresAt == null || !newExpiresAt.isAfter(previous) || !newExpiresAt.isAfter(now)) {
			throw invalid("newExpiresAt", "Must be later than both now and the current expiry");
		}
		if (newExpiresAt.isAfter(now.plus(MAX_HORIZON))) {
			throw invalid("newExpiresAt", "Must be within 5 years");
		}
		boolean wasExpired = !window.isOpen(now);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		EnrollmentAccessExtension extension = extensionRepository.save(new EnrollmentAccessExtension(
				tenantContext.getTenantId(), enrollmentId, previous, newExpiresAt, checkedReason, actorId, now));

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("before", previous.toString());
		metadata.put("after", newExpiresAt.toString());
		metadata.put("wasExpired", wasExpired);
		metadata.put("extensionId", extension.getId().toString());
		auditLogApi.record(new AuditLogEntry(actorId, "enrollment.access_extended", "enrollment", enrollmentId,
				checkedReason, metadata));
		studentLookupApi.getStudentSummariesByUserId(List.of(enrollment.getStudentId()))
			.stream()
			.findFirst()
			.map(StudentSummary::studentProfileId)
			.ifPresent(profileId -> {
				Map<String, Object> profileMetadata = new HashMap<>(metadata);
				profileMetadata.put("enrollmentId", enrollmentId.toString());
				auditLogApi.record(new AuditLogEntry(actorId, "enrollment.access_extended", "student_profile",
						profileId, checkedReason, profileMetadata));
			});
		return toView(extension);
	}

	private Enrollment requireEnrollment(UUID enrollmentId) {
		return enrollmentRepository.findById(enrollmentId)
			.orElseThrow(() -> new NotFoundException("Enrollment not found"));
	}

	private static AccessExtensionView toView(EnrollmentAccessExtension extension) {
		return new AccessExtensionView(extension.getId(), extension.getEnrollmentId(),
				extension.getPreviousExpiresAt(), extension.getNewExpiresAt(), extension.getReason(),
				extension.getGrantedBy(), extension.getGrantedAt());
	}

	private static String requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw invalid("reason", "A reason is required");
		}
		String trimmed = reason.strip();
		if (trimmed.length() > MAX_REASON_LENGTH) {
			throw invalid("reason", "Must be at most 500 characters");
		}
		return trimmed;
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("Validation failed", List.of(new FieldError(field, message)));
	}

}
