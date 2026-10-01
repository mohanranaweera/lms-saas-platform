package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DeviceManagementApi;
import com.lms.identityaccessservice.api.DeviceSlotSummary;
import com.lms.identityaccessservice.api.DeviceView;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 10 - the Student detail Devices tab. Resolves the {@code
 * student_profile} id (tenant-scoped, so another tenant id is a 404) and
 * delegates to {@link DeviceManagementApi}, which enforces {@code DEVICES}
 * permissions and writes the device-level audit rows. Each change also writes
 * a {@code student_profile} audit row in the same transaction, so it shows on
 * the student Activity tab (same pattern as enroll/revoke, ADR-016).
 */
@Service
public class StudentDeviceService {

	private final StudentProfileRepository studentProfileRepository;

	private final DeviceManagementApi deviceManagementApi;

	private final AuditLogApi auditLogApi;

	public StudentDeviceService(StudentProfileRepository studentProfileRepository,
			DeviceManagementApi deviceManagementApi, AuditLogApi auditLogApi) {
		this.studentProfileRepository = studentProfileRepository;
		this.deviceManagementApi = deviceManagementApi;
		this.auditLogApi = auditLogApi;
	}

	@Transactional(readOnly = true)
	public DeviceSlotSummary getDevices(UUID studentProfileId) {
		return deviceManagementApi.getDevices(requireProfile(studentProfileId).getUserId());
	}

	@Transactional
	public DeviceView resetDevice(UUID studentProfileId, UUID deviceId, String reason) {
		StudentProfile profile = requireProfile(studentProfileId);
		DeviceView device = deviceManagementApi.resetDevice(profile.getUserId(), deviceId, reason);
		recordOnProfile(profile, "student.device_reset", reason, Map.of("deviceId", deviceId.toString(), "count", 1));
		return device;
	}

	@Transactional
	public int resetAllDevices(UUID studentProfileId, String reason) {
		StudentProfile profile = requireProfile(studentProfileId);
		int count = deviceManagementApi.resetAllDevices(profile.getUserId(), reason);
		recordOnProfile(profile, "student.device_reset", reason, Map.of("deviceId", "all", "count", count));
		return count;
	}

	/** {@code maxDevices} null clears the student exception (back to course/tenant/plan precedence). */
	@Transactional
	public DeviceSlotSummary setDeviceLimit(UUID studentProfileId, Integer maxDevices, String reason) {
		StudentProfile profile = requireProfile(studentProfileId);
		DeviceSlotSummary summary = maxDevices == null
				? deviceManagementApi.clearDeviceLimitOverride(profile.getUserId(), reason)
				: deviceManagementApi.setDeviceLimitOverride(profile.getUserId(), maxDevices, reason);
		recordOnProfile(profile, "student.device_limit_changed", reason,
				Map.of("maxDevices", maxDevices == null ? "default" : maxDevices));
		return summary;
	}

	private StudentProfile requireProfile(UUID studentProfileId) {
		return studentProfileRepository.findById(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student account not found"));
	}

	private void recordOnProfile(StudentProfile profile, String action, String reason, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), action, "student_profile",
				profile.getId(), reason == null || reason.isBlank() ? null : reason.strip(), metadata));
	}

}
