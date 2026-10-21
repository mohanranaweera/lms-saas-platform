package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.enrollmentmanagement.domain.CourseAccessPolicy;
import com.lms.enrollmentmanagement.domain.TenantAccessPolicy;
import com.lms.enrollmentmanagement.repository.CourseAccessPolicyRepository;
import com.lms.enrollmentmanagement.repository.TenantAccessPolicyRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant and course access-policy settings (Wave 10): the grace period (tenant
 * default, course override) and the course-level device limit. {@code
 * ACCESS_EXPIRY} V / C/E; setting or clearing a course device limit also needs
 * {@code DEVICES} C/E. Every change is audit-logged (before/after) in the same
 * transaction. A course id from another tenant is not found (404).
 */
@Service
public class AccessPolicyAdminService {

	public record TenantAccessPolicyView(int gracePeriodDays, Instant updatedAt) {
	}

	public record CourseAccessPolicyView(UUID courseId, String courseName, Integer gracePeriodDays,
			Integer maxDevices, Instant updatedAt) {
	}

	private final TenantAccessPolicyRepository tenantPolicyRepository;

	private final CourseAccessPolicyRepository coursePolicyRepository;

	private final CourseLookupApi courseLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public AccessPolicyAdminService(TenantAccessPolicyRepository tenantPolicyRepository,
			CourseAccessPolicyRepository coursePolicyRepository, CourseLookupApi courseLookupApi,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.tenantPolicyRepository = tenantPolicyRepository;
		this.coursePolicyRepository = coursePolicyRepository;
		this.courseLookupApi = courseLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public TenantAccessPolicyView getTenantPolicy() {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW);
		return tenantPolicyRepository.findForCurrentTenant()
			.map(policy -> new TenantAccessPolicyView(policy.getGracePeriodDays(), policy.getUpdatedAt()))
			.orElse(new TenantAccessPolicyView(0, null));
	}

	@Transactional
	public TenantAccessPolicyView updateTenantPolicy(int gracePeriodDays) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Optional<TenantAccessPolicy> existing = tenantPolicyRepository.findForCurrentTenant();
		int before = existing.map(TenantAccessPolicy::getGracePeriodDays).orElse(0);
		TenantAccessPolicy policy = existing.orElseGet(() -> new TenantAccessPolicy(tenantContext.getTenantId()));
		policy.update(gracePeriodDays, actorId, Instant.now());
		policy = tenantPolicyRepository.save(policy);
		auditLogApi.record(new AuditLogEntry(actorId, "access_policy.updated", "tenant_access_policy", policy.getId(),
				null, Map.of("before", Map.of("gracePeriodDays", before), "after",
						Map.of("gracePeriodDays", gracePeriodDays))));
		return new TenantAccessPolicyView(policy.getGracePeriodDays(), policy.getUpdatedAt());
	}

	@Transactional(readOnly = true)
	public List<CourseAccessPolicyView> listCoursePolicies() {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW);
		List<CourseAccessPolicy> policies = coursePolicyRepository.findAll();
		Map<UUID, String> names = courseNames(
				policies.stream().map(CourseAccessPolicy::getCourseId).collect(Collectors.toSet()));
		return policies.stream()
			.map(policy -> toView(policy, names.get(policy.getCourseId())))
			.sorted(Comparator.comparing(view -> view.courseName() == null ? "" : view.courseName()))
			.toList();
	}

	/**
	 * Creates/updates a course override. Both values null removes the override
	 * (the course falls back to the tenant grace period and to the student/
	 * tenant/plan device limit).
	 */
	@Transactional
	public Optional<CourseAccessPolicyView> upsertCoursePolicy(UUID courseId, Integer gracePeriodDays,
			Integer maxDevices) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.CREATE_EDIT);
		String courseName = requireCourseName(courseId);
		Optional<CourseAccessPolicy> existing = coursePolicyRepository.findByCourseId(courseId);
		Integer previousMaxDevices = existing.map(CourseAccessPolicy::getMaxDevices).orElse(null);
		if (!java.util.Objects.equals(maxDevices, previousMaxDevices)) {
			permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Map<String, Object> before = describe(existing.map(CourseAccessPolicy::getGracePeriodDays).orElse(null),
				previousMaxDevices);
		if (gracePeriodDays == null && maxDevices == null) {
			existing.ifPresent(policy -> {
				coursePolicyRepository.delete(policy);
				audit(actorId, policy.getId(), courseId, before, describe(null, null));
			});
			return Optional.empty();
		}
		CourseAccessPolicy policy = existing
			.orElseGet(() -> new CourseAccessPolicy(tenantContext.getTenantId(), courseId));
		policy.update(gracePeriodDays, maxDevices, actorId, Instant.now());
		policy = coursePolicyRepository.save(policy);
		audit(actorId, policy.getId(), courseId, before, describe(gracePeriodDays, maxDevices));
		return Optional.of(toView(policy, courseName));
	}

	private void audit(UUID actorId, UUID policyId, UUID courseId, Map<String, Object> before,
			Map<String, Object> after) {
		auditLogApi.record(new AuditLogEntry(actorId, "course_access_policy.updated", "course_access_policy", policyId,
				null, Map.of("courseId", courseId.toString(), "before", before, "after", after)));
	}

	private String requireCourseName(UUID courseId) {
		return courseLookupApi.getCourseSummaries(Set.of(courseId))
			.stream()
			.findFirst()
			.map(CourseSummary::name)
			.orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private Map<UUID, String> courseNames(Set<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		return courseLookupApi.getCourseSummaries(courseIds)
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));
	}

	private static CourseAccessPolicyView toView(CourseAccessPolicy policy, String courseName) {
		return new CourseAccessPolicyView(policy.getCourseId(), courseName, policy.getGracePeriodDays(),
				policy.getMaxDevices(), policy.getUpdatedAt());
	}

	private static Map<String, Object> describe(Integer gracePeriodDays, Integer maxDevices) {
		Map<String, Object> values = new HashMap<>();
		values.put("gracePeriodDays", gracePeriodDays == null ? "default" : gracePeriodDays);
		values.put("maxDevices", maxDevices == null ? "default" : maxDevices);
		return values;
	}

}
