package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DeviceLimitSource;
import com.lms.identityaccessservice.api.DevicePolicyView;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.config.DeviceProperties;
import com.lms.identityaccessservice.domain.StudentDeviceLimitOverride;
import com.lms.identityaccessservice.domain.TenantDevicePolicy;
import com.lms.identityaccessservice.repository.StudentDeviceLimitOverrideRepository;
import com.lms.identityaccessservice.repository.TenantDevicePolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place the device-limit precedence is resolved (Wave 10,
 * {@code docs/architecture/authentication-authorization.md} §6): student
 * exception, then course override, then tenant policy, then plan default -
 * the most specific configured level wins, never a sum or average. Also owns
 * the tenant device policy read/update ({@code DEVICES} V / C/E, audit-logged).
 */
@Service
public class DevicePolicyService {

	/** One resolved limit and the precedence level that produced it. */
	public record ResolvedLimit(int limit, DeviceLimitSource source) {
	}

	/** The tenant policy with defaults applied for a tenant that never saved one. */
	public record EffectivePolicy(Integer tenantMaxDevices, int resetCooldownHours, boolean studentSelfServiceEnabled,
			Instant updatedAt) {

		public Duration resetCooldown() {
			return Duration.ofHours(resetCooldownHours);
		}

	}

	private final TenantDevicePolicyRepository policyRepository;

	private final StudentDeviceLimitOverrideRepository overrideRepository;

	private final DeviceProperties deviceProperties;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	public DevicePolicyService(TenantDevicePolicyRepository policyRepository,
			StudentDeviceLimitOverrideRepository overrideRepository, DeviceProperties deviceProperties,
			TenantContext tenantContext, PermissionCheckService permissionCheckService, AuditLogApi auditLogApi) {
		this.policyRepository = policyRepository;
		this.overrideRepository = overrideRepository;
		this.deviceProperties = deviceProperties;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
	}

	@Transactional(readOnly = true)
	public EffectivePolicy currentPolicy() {
		return policyRepository.findForCurrentTenant()
			.map(policy -> new EffectivePolicy(policy.getMaxDevices(), policy.getResetCooldownHours(),
					policy.isStudentSelfServiceEnabled(), policy.getUpdatedAt()))
			.orElseGet(() -> new EffectivePolicy(null, TenantDevicePolicy.DEFAULT_RESET_COOLDOWN_HOURS, false, null));
	}

	/** Login-time limit: student exception, then tenant policy, then plan default (no course is known at login). */
	@Transactional(readOnly = true)
	public ResolvedLimit resolveLoginLimit(UUID userId) {
		return resolve(userId, null);
	}

	/** Course-content limit: student exception, then {@code courseMaxDevices}, then tenant, then plan. */
	@Transactional(readOnly = true)
	public ResolvedLimit resolve(UUID userId, Integer courseMaxDevices) {
		Optional<StudentDeviceLimitOverride> studentOverride = overrideRepository.findByUserId(userId);
		if (studentOverride.isPresent()) {
			return new ResolvedLimit(studentOverride.get().getMaxDevices(), DeviceLimitSource.STUDENT);
		}
		if (courseMaxDevices != null) {
			return new ResolvedLimit(courseMaxDevices, DeviceLimitSource.COURSE);
		}
		Integer tenantMax = currentPolicy().tenantMaxDevices();
		if (tenantMax != null) {
			return new ResolvedLimit(tenantMax, DeviceLimitSource.TENANT);
		}
		return new ResolvedLimit(deviceProperties.getPlanDefaultLimit(), DeviceLimitSource.PLAN);
	}

	@Transactional(readOnly = true)
	public DevicePolicyView getPolicy() {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.VIEW);
		return toView(currentPolicy());
	}

	@Transactional
	public DevicePolicyView updatePolicy(Integer maxDevices, int resetCooldownHours,
			boolean studentSelfServiceEnabled) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		EffectivePolicy before = currentPolicy();
		TenantDevicePolicy policy = policyRepository.findForCurrentTenant()
			.orElseGet(() -> new TenantDevicePolicy(tenantContext.getTenantId()));
		policy.update(maxDevices, resetCooldownHours, studentSelfServiceEnabled, actorId, Instant.now());
		policy = policyRepository.save(policy);

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("before", describe(before.tenantMaxDevices(), before.resetCooldownHours(),
				before.studentSelfServiceEnabled()));
		metadata.put("after", describe(maxDevices, resetCooldownHours, studentSelfServiceEnabled));
		auditLogApi.record(new AuditLogEntry(actorId, "device_policy.updated", "tenant_device_policy",
				policy.getId(), null, metadata));
		return toView(currentPolicy());
	}

	public int planDefaultLimit() {
		return deviceProperties.getPlanDefaultLimit();
	}

	private DevicePolicyView toView(EffectivePolicy policy) {
		int effective = policy.tenantMaxDevices() != null ? policy.tenantMaxDevices()
				: deviceProperties.getPlanDefaultLimit();
		return new DevicePolicyView(policy.tenantMaxDevices(), effective, deviceProperties.getPlanDefaultLimit(),
				policy.resetCooldownHours(), policy.studentSelfServiceEnabled(), policy.updatedAt());
	}

	private static Map<String, Object> describe(Integer maxDevices, int cooldownHours, boolean selfService) {
		Map<String, Object> values = new HashMap<>();
		values.put("maxDevices", maxDevices);
		values.put("resetCooldownHours", cooldownHours);
		values.put("studentSelfServiceEnabled", selfService);
		return values;
	}

}
