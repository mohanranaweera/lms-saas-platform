package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.DeviceSelfServiceDisabledException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DeviceAccessApi;
import com.lms.identityaccessservice.api.DeviceLimitSource;
import com.lms.identityaccessservice.api.DeviceManagementApi;
import com.lms.identityaccessservice.api.DeviceRevokedEvent;
import com.lms.identityaccessservice.api.DeviceSlotSummary;
import com.lms.identityaccessservice.api.DeviceView;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.domain.Device;
import com.lms.identityaccessservice.domain.DeviceRevocationSource;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.StudentDeviceLimitOverride;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.DeviceRepository;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.StudentDeviceLimitOverrideRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device listing, revocation/reset and student device-limit exceptions (Wave
 * 10). Every staff method enforces {@code DEVICES} permissions itself; every
 * lookup goes through tenant-scoped repositories, so an id from another tenant
 * is simply not found (404). Revoking a device revokes its sessions in
 * Postgres and evicts them from the Redis fast path in the same transaction -
 * the device is signed out on its next request - and starts the tenant reset
 * cooldown on its slot. Devices are never deleted.
 */
@Service
public class DeviceManagementService implements DeviceManagementApi, DeviceAccessApi {

	private static final String SELF_REVOKE_REASON = "Removed by the account holder";

	private static final int MAX_REASON_LENGTH = 500;

	/** Active devices first (oldest registration first), then removed ones (most recently removed first). */
	private static final Comparator<Device> DISPLAY_ORDER = (a, b) -> {
		if (a.isActive() != b.isActive()) {
			return a.isActive() ? -1 : 1;
		}
		return a.isActive() ? a.getFirstSeenAt().compareTo(b.getFirstSeenAt())
				: b.getRevokedAt().compareTo(a.getRevokedAt());
	};

	private final DeviceRepository deviceRepository;

	private final DeviceSessionRepository deviceSessionRepository;

	private final StudentDeviceLimitOverrideRepository overrideRepository;

	private final TenantUserRepository tenantUserRepository;

	private final DevicePolicyService devicePolicyService;

	private final DeviceSessionCacheService cacheService;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final ApplicationEventPublisher eventPublisher;

	public DeviceManagementService(DeviceRepository deviceRepository, DeviceSessionRepository deviceSessionRepository,
			StudentDeviceLimitOverrideRepository overrideRepository, TenantUserRepository tenantUserRepository,
			DevicePolicyService devicePolicyService, DeviceSessionCacheService cacheService,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi,
			ApplicationEventPublisher eventPublisher) {
		this.deviceRepository = deviceRepository;
		this.deviceSessionRepository = deviceSessionRepository;
		this.overrideRepository = overrideRepository;
		this.tenantUserRepository = tenantUserRepository;
		this.devicePolicyService = devicePolicyService;
		this.cacheService = cacheService;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.eventPublisher = eventPublisher;
	}

	// ---- staff (DeviceManagementApi) ----

	@Override
	@Transactional(readOnly = true)
	public DeviceSlotSummary getDevices(UUID userId) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.VIEW);
		return summarize(requireUser(userId), null);
	}

	@Override
	@Transactional
	public DeviceView resetDevice(UUID userId, UUID deviceId, String reason) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		String checkedReason = requireReason(reason);
		TenantUser user = requireUser(userId);
		Device device = requireDeviceOf(user.getId(), deviceId);
		if (!device.isActive()) {
			throw new ConflictException("This device has already been removed");
		}
		revoke(device, actorId(), DeviceRevocationSource.ADMIN, checkedReason, "device.reset");
		return toView(device, null);
	}

	@Override
	@Transactional
	public int resetAllDevices(UUID userId, String reason) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		String checkedReason = requireReason(reason);
		TenantUser user = requireUser(userId);
		UUID actorId = actorId();
		List<Device> active = deviceRepository.findActiveByUserIdOldestFirst(user.getId());
		active.forEach(device -> revoke(device, actorId, DeviceRevocationSource.ADMIN, checkedReason, "device.reset"));
		return active.size();
	}

	@Override
	@Transactional
	public DeviceSlotSummary setDeviceLimitOverride(UUID userId, int maxDevices, String reason) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		String checkedReason = requireReason(reason);
		if (maxDevices < 1 || maxDevices > 20) {
			throw invalid("maxDevices", "Must be between 1 and 20");
		}
		TenantUser user = requireUser(userId);
		if (user.getRole() != Role.STUDENT) {
			throw new ConflictException("Device limits apply to student accounts only");
		}
		UUID actorId = actorId();
		Optional<StudentDeviceLimitOverride> existing = overrideRepository.findByUserId(user.getId());
		Object before = existing.<Object>map(StudentDeviceLimitOverride::getMaxDevices).orElse("none");
		StudentDeviceLimitOverride override = existing
			.orElseGet(() -> new StudentDeviceLimitOverride(user.getTenantId(), user.getId()));
		override.set(maxDevices, checkedReason, actorId, Instant.now());
		overrideRepository.save(override);
		auditLogApi.record(new AuditLogEntry(actorId, "device_limit_override.set", "tenant_user", user.getId(),
				checkedReason, Map.of("before", before, "after", maxDevices)));
		return summarize(user, null);
	}

	@Override
	@Transactional
	public DeviceSlotSummary clearDeviceLimitOverride(UUID userId, String reason) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		String checkedReason = requireReason(reason);
		TenantUser user = requireUser(userId);
		UUID actorId = actorId();
		overrideRepository.findByUserId(user.getId()).ifPresent(override -> {
			overrideRepository.delete(override);
			auditLogApi.record(new AuditLogEntry(actorId, "device_limit_override.cleared", "tenant_user",
					user.getId(), checkedReason, Map.of("before", override.getMaxDevices(), "after", "none")));
		});
		return summarize(user, null);
	}

	// ---- the signed-in user (My Devices) ----

	@Transactional(readOnly = true)
	public DeviceSlotSummary getMyDevices() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		return summarize(requireUser(principal.userId()), currentDeviceId(principal));
	}

	/**
	 * The signed-in user removes one of their OTHER devices. A student needs the
	 * tenant to have enabled self-service. The reset cooldown applies exactly as
	 * for an admin reset, so removing and re-adding devices cannot be used to
	 * share an account. The current device is removed by signing out instead.
	 */
	@Transactional
	public DeviceSlotSummary revokeMyDevice(UUID deviceId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		TenantUser user = requireUser(principal.userId());
		if (user.getRole() == Role.STUDENT && !devicePolicyService.currentPolicy().studentSelfServiceEnabled()) {
			throw new DeviceSelfServiceDisabledException(
					"Your institute manages your devices. Ask your institute to remove a device.");
		}
		Device device = requireDeviceOf(user.getId(), deviceId);
		UUID currentDeviceId = currentDeviceId(principal);
		if (device.getId().equals(currentDeviceId)) {
			throw new ConflictException("This is the device you are using. Sign out to remove it.");
		}
		if (!device.isActive()) {
			throw new ConflictException("This device has already been removed");
		}
		revoke(device, user.getId(), DeviceRevocationSource.STUDENT, SELF_REVOKE_REASON, "device.revoked_by_user");
		return summarize(user, currentDeviceId);
	}

	// ---- access policy (DeviceAccessApi) ----

	@Override
	@Transactional(readOnly = true)
	public boolean isSessionDeviceAuthorized(UUID userId, UUID sessionId, Integer courseMaxDevices) {
		if (courseMaxDevices == null) {
			return true;
		}
		DevicePolicyService.ResolvedLimit limit = devicePolicyService.resolve(userId, courseMaxDevices);
		if (limit.source() != DeviceLimitSource.COURSE) {
			// A student exception outranks the course limit; it is enforced at login.
			return true;
		}
		UUID deviceId = deviceSessionRepository.findById(sessionId).map(DeviceSession::getDeviceId).orElse(null);
		if (deviceId == null) {
			return false;
		}
		return isAmongAuthorizedDevices(userId, deviceId, limit.limit());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> findActiveSessionDeviceId(UUID userId, UUID sessionId) {
		return deviceSessionRepository.findById(sessionId)
			.map(DeviceSession::getDeviceId)
			.flatMap(deviceRepository::findById)
			.filter(device -> device.getUserId().equals(userId) && device.isActive())
			.map(Device::getId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isDeviceAuthorized(UUID userId, UUID deviceId, Integer courseMaxDevices) {
		boolean active = deviceRepository.findById(deviceId)
			.filter(device -> device.getUserId().equals(userId) && device.isActive())
			.isPresent();
		if (!active) {
			return false;
		}
		if (courseMaxDevices == null) {
			return true;
		}
		DevicePolicyService.ResolvedLimit limit = devicePolicyService.resolve(userId, courseMaxDevices);
		return limit.source() != DeviceLimitSource.COURSE || isAmongAuthorizedDevices(userId, deviceId, limit.limit());
	}

	private boolean isAmongAuthorizedDevices(UUID userId, UUID deviceId, int limit) {
		return deviceRepository.findActiveByUserIdOldestFirst(userId)
			.stream()
			.limit(limit)
			.anyMatch(device -> device.getId().equals(deviceId));
	}

	// ---- internals ----

	private void revoke(Device device, UUID actorId, DeviceRevocationSource source, String reason, String action) {
		Instant now = Instant.now();
		device.revoke(actorId, source, reason, now, devicePolicyService.currentPolicy().resetCooldown());
		deviceRepository.save(device);
		for (DeviceSession session : deviceSessionRepository.findActiveForDevice(device.getId())) {
			session.revoke(now);
			deviceSessionRepository.save(session);
			cacheService.evict(session.getId());
		}
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("userId", device.getUserId().toString());
		metadata.put("label", device.getLabel());
		metadata.put("before", "active");
		metadata.put("after", "revoked");
		metadata.put("slotReusableAt", device.getSlotReusableAt().toString());
		auditLogApi.record(new AuditLogEntry(actorId, action, "device", device.getId(), reason, metadata));
		// Same transaction: listeners (VIDEO Phase 8 player installations) revoke what hangs off this device.
		eventPublisher.publishEvent(new DeviceRevokedEvent(device.getTenantId(), device.getUserId(), device.getId(),
				actorId, now));
	}

	private DeviceSlotSummary summarize(TenantUser user, UUID currentDeviceId) {
		Instant now = Instant.now();
		List<Device> devices = deviceRepository.findAllByUserIdOldestFirst(user.getId());
		DevicePolicyService.EffectivePolicy policy = devicePolicyService.currentPolicy();
		Optional<StudentDeviceLimitOverride> override = overrideRepository.findByUserId(user.getId());
		boolean student = user.getRole() == Role.STUDENT;
		DevicePolicyService.ResolvedLimit limit = student ? devicePolicyService.resolveLoginLimit(user.getId()) : null;
		int occupied = (int) devices.stream().filter(device -> device.occupiesSlot(now)).count();
		Instant nextSlotFreeAt = null;
		if (limit != null && occupied >= limit.limit()) {
			nextSlotFreeAt = devices.stream()
				.filter(device -> !device.isActive() && device.occupiesSlot(now))
				.map(Device::getSlotReusableAt)
				.min(Comparator.naturalOrder())
				.orElse(null);
		}
		List<DeviceView> views = devices.stream()
			.sorted(DISPLAY_ORDER)
			.map(device -> toView(device, currentDeviceId))
			.toList();
		return new DeviceSlotSummary(views, limit == null ? null : limit.limit(), limit == null ? null : limit.source(),
				occupied, nextSlotFreeAt, override.map(StudentDeviceLimitOverride::getMaxDevices).orElse(null),
				override.map(StudentDeviceLimitOverride::getReason).orElse(null),
				!student || policy.studentSelfServiceEnabled(), policy.resetCooldownHours());
	}

	private static DeviceView toView(Device device, UUID currentDeviceId) {
		return new DeviceView(device.getId(), device.getLabel(), device.getStatus().name(), device.getFirstSeenAt(),
				device.getLastSeenAt(), device.getLastIp(), device.getRevokedAt(),
				device.getRevocationSource() == null ? null : device.getRevocationSource().name(),
				device.getRevokeReason(), device.getSlotReusableAt(), device.getId().equals(currentDeviceId));
	}

	private TenantUser requireUser(UUID userId) {
		return tenantUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
	}

	private Device requireDeviceOf(UUID userId, UUID deviceId) {
		return deviceRepository.findById(deviceId)
			.filter(device -> device.getUserId().equals(userId))
			.orElseThrow(() -> new NotFoundException("Device not found"));
	}

	private UUID currentDeviceId(AuthenticatedPrincipal principal) {
		return deviceSessionRepository.findById(principal.sessionId()).map(DeviceSession::getDeviceId).orElse(null);
	}

	private static UUID actorId() {
		return AuthenticatedPrincipalHolder.get().userId();
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
