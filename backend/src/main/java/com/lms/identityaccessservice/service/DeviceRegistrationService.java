package com.lms.identityaccessservice.service;

import com.lms.common.error.DeviceLimitReachedException;
import com.lms.identityaccessservice.domain.Device;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.DeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side device registration at login and device verification at refresh
 * (Wave 10, ADR-007 §2, {@code docs/architecture/authentication-authorization.md}
 * §5-§6). Identity is the server-issued token in the {@code lms_device_id}
 * cookie - never User-Agent or IP.
 *
 * <p>Runs inside the login transaction ({@link Propagation#MANDATORY}), so the
 * device row and the new session commit or roll back together; a refused
 * login leaves nothing behind. Together those two rows ARE the registration /
 * login-activity record ({@code device.first_seen_at}/{@code label}/{@code
 * last_ip}, {@code device_session.issued_at}, V5/V64) - no separate audit row
 * is written for a registration (the {@code security.md} audit list covers
 * resets, not sign-ins), so a login never depends on the audit log.
 */
@Service
public class DeviceRegistrationService {

	/** What the login should attach to the new session and send back as the device cookie. */
	public record DeviceBinding(UUID deviceId, String tokenHash, String rawToken) {
	}

	private final DeviceRepository deviceRepository;

	private final DevicePolicyService devicePolicyService;

	public DeviceRegistrationService(DeviceRepository deviceRepository, DevicePolicyService devicePolicyService) {
		this.deviceRepository = deviceRepository;
		this.devicePolicyService = devicePolicyService;
	}

	/**
	 * Reuses the active device named by {@code rawCookieToken} for this user, or
	 * registers a new one. For a STUDENT a new device needs a free slot under the
	 * resolved login limit (active devices plus revoked ones still in cooldown
	 * count as taken); otherwise {@link DeviceLimitReachedException}. Staff
	 * devices are registered but not limited.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public DeviceBinding bindAtLogin(TenantUser user, String rawCookieToken, String userAgent, String ip) {
		Instant now = Instant.now();
		deviceRepository.acquireRegistrationLock(user.getTenantId() + ":" + user.getId());

		if (DeviceTokens.isPlausible(rawCookieToken)) {
			String hash = DeviceTokens.hash(rawCookieToken);
			Optional<Device> known = deviceRepository.findByUserIdAndTokenHash(user.getId(), hash)
				.filter(Device::isActive);
			if (known.isPresent()) {
				Device device = known.get();
				device.touch(now, ip);
				deviceRepository.save(device);
				return new DeviceBinding(device.getId(), hash, rawCookieToken);
			}
		}

		if (user.getRole() == Role.STUDENT) {
			requireFreeSlot(user.getId(), now);
		}

		String rawToken = DeviceTokens.generate();
		String hash = DeviceTokens.hash(rawToken);
		Device device = deviceRepository
			.save(Device.register(user.getTenantId(), user.getId(), hash, DeviceLabels.fromUserAgent(userAgent), ip,
					now));
		return new DeviceBinding(device.getId(), hash, rawToken);
	}

	/**
	 * A refresh is allowed only from the device the session was issued to: the
	 * presented device token must hash to the session's device and that device
	 * must still be active. Sessions issued before Wave 10 carry no device and
	 * are refused (one re-login registers the device).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean verifyForRefresh(DeviceSession session, String rawCookieToken, String ip) {
		if (session.getDeviceId() == null || !DeviceTokens.isPlausible(rawCookieToken)) {
			return false;
		}
		if (!Objects.equals(DeviceTokens.hash(rawCookieToken), session.getDeviceIdentifierHash())) {
			return false;
		}
		Optional<Device> device = deviceRepository.findById(session.getDeviceId()).filter(Device::isActive);
		if (device.isEmpty() || !device.get().getUserId().equals(session.getUserId())) {
			return false;
		}
		device.get().touch(Instant.now(), ip);
		deviceRepository.save(device.get());
		return true;
	}

	private void requireFreeSlot(UUID userId, Instant now) {
		int limit = devicePolicyService.resolveLoginLimit(userId).limit();
		List<Device> devices = deviceRepository.findAllByUserIdOldestFirst(userId);
		long occupied = devices.stream().filter(device -> device.occupiesSlot(now)).count();
		if (occupied < limit) {
			return;
		}
		long active = devices.stream().filter(Device::isActive).count();
		Instant nextFree = devices.stream()
			.filter(device -> !device.isActive() && device.occupiesSlot(now))
			.map(Device::getSlotReusableAt)
			.min(Instant::compareTo)
			.orElse(null);
		String message = "You have reached the maximum of " + limit + (limit == 1 ? " device" : " devices")
				+ " for your account. Remove a device you no longer use or ask your institute to reset one.";
		if (active < limit && nextFree != null) {
			message = "A device was recently removed from your account. You can sign in on a new device after "
					+ nextFree + ".";
		}
		throw new DeviceLimitReachedException(message);
	}

}
