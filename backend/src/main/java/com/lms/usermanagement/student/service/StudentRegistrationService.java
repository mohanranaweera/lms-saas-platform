package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.domain.StudentRegistrationOtp;
import com.lms.usermanagement.student.domain.StudentRegistrationStatus;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import com.lms.usermanagement.student.repository.StudentRegistrationOtpRepository;
import com.lms.usermanagement.student.web.dto.StudentRegistrationRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates public student self-registration (Wave 3, PAR-03-01/master
 * instruction §9-10) - tenant-driven, gated by {@code
 * ConfigDomain.STUDENT}'s registration-policy properties. Tenant identity is
 * always the request's already-resolved {@link TenantContext} (from {@code
 * TenantResolutionFilter}'s subdomain resolution) - never a client-supplied
 * field, per {@code .claude/rules/tenancy.md}.
 *
 * <p>{@link #sendOtp} delegates its DB write to {@link
 * StudentRegistrationOtpWriter}, a separate transactional bean, then sends
 * the email AFTER that transaction commits - per {@code
 * .claude/rules/backend.md}'s "do not span a transaction across an outbound
 * call" rule, {@link MessagingProviderApi#sendEmail} is never called from
 * inside an open transaction.
 */
@Service
public class StudentRegistrationService {

	private static final int MAX_VERIFY_ATTEMPTS = 5;

	/** How long a successfully-verified OTP remains usable as registration evidence. */
	private static final long VERIFIED_WINDOW_MINUTES = 15;

	private final TenantContext tenantContext;

	private final TenantConfigApi tenantConfigApi;

	private final StudentProfileRepository studentProfileRepository;

	private final StudentRegistrationOtpRepository otpRepository;

	private final StudentRegistrationOtpWriter otpWriter;

	private final UserProvisioningApi userProvisioningApi;

	private final MessagingProviderApi messagingProviderApi;

	private final PasswordEncoder passwordEncoder;

	private final AuditLogApi auditLogApi;

	private final StudentProfileFieldsService studentProfileFieldsService;

	public StudentRegistrationService(TenantContext tenantContext, TenantConfigApi tenantConfigApi,
			StudentProfileRepository studentProfileRepository, StudentRegistrationOtpRepository otpRepository,
			StudentRegistrationOtpWriter otpWriter, UserProvisioningApi userProvisioningApi,
			MessagingProviderApi messagingProviderApi, PasswordEncoder passwordEncoder, AuditLogApi auditLogApi,
			StudentProfileFieldsService studentProfileFieldsService) {
		this.studentProfileFieldsService = studentProfileFieldsService;
		this.tenantContext = tenantContext;
		this.tenantConfigApi = tenantConfigApi;
		this.studentProfileRepository = studentProfileRepository;
		this.otpRepository = otpRepository;
		this.otpWriter = otpWriter;
		this.userProvisioningApi = userProvisioningApi;
		this.messagingProviderApi = messagingProviderApi;
		this.passwordEncoder = passwordEncoder;
		this.auditLogApi = auditLogApi;
	}

	/**
	 * Sends a new OTP to {@code email}, rate-limited per (tenant, email) by
	 * {@link StudentRegistrationOtpWriter#issueOtp} (a separate, genuinely
	 * transactional bean - see that class's javadoc for why). Uniform
	 * outcome regardless of whether this email already has an account -
	 * registration has not happened yet at this point, so there is nothing
	 * to enumerate about account existence; the only real signal exposed is
	 * the rate-limit itself.
	 */
	public void sendOtp(String email) {
		requirePublicRegistrationEnabled();
		String rawOtp = otpWriter.issueOtp(email);
		// Sent AFTER issueOtp's own transaction commits - never inside an
		// open DB transaction, per .claude/rules/backend.md.
		messagingProviderApi.sendEmail(email, "Your verification code",
				"Your verification code is " + rawOtp + ". It expires in " + StudentRegistrationOtpWriter.OTP_TTL_MINUTES
						+ " minutes.");
	}

	/**
	 * Verifies {@code otp} against the most recent unconsumed row for {@code
	 * email}. Uniform failure ({@link ConflictException}, generic message) on
	 * every failure mode - wrong code, expired, already consumed, attempt
	 * limit exhausted, or no row at all - never distinguishing which, per
	 * {@code .claude/rules/security.md}'s anti-enumeration requirement.
	 */
	@Transactional
	public void verifyOtp(String email, String rawOtp) {
		StudentRegistrationOtp otp = otpRepository.findMostRecentByEmail(email).orElse(null);
		if (otp == null || otp.isConsumed() || otp.isExpired(Instant.now())
				|| otp.getAttemptCount() >= MAX_VERIFY_ATTEMPTS) {
			throw new ConflictException("Invalid or expired verification code");
		}
		if (!passwordEncoder.matches(rawOtp, otp.getOtpHash())) {
			otp.recordFailedAttempt();
			otpRepository.save(otp);
			throw new ConflictException("Invalid or expired verification code");
		}
		otp.consume();
		otpRepository.save(otp);
	}

	/**
	 * Registers a new student account, gated by the calling tenant's {@code
	 * ConfigDomain.STUDENT} policy. See class javadoc for the
	 * transaction/email-send boundary discipline - unlike {@link
	 * #sendOtp}, this method itself needs no outbound call, so it is
	 * {@code @Transactional} directly.
	 */
	@Transactional
	public StudentAccount register(StudentRegistrationRequest request) {
		Map<String, Object> config = tenantConfigApi.resolveDomain(tenantContext.getTenantId(), ConfigDomain.STUDENT);
		if (!flag(config, "public_registration_enabled", true)) {
			// Cross-tenant-404-style convention: availability is never
			// revealed via a distinguishable status code.
			throw new NotFoundException("Student self-registration is not available");
		}

		// STU-PROF-01: validated against the tenant's student profile field
		// settings before any account is created.
		Map<StudentProfileField, Object> profileValues = studentProfileFieldsService
			.validateRegistrationValues(registrationValues(request));

		if (flag(config, "otp_required", false)) {
			requireRecentlyVerifiedOtp(request.email());
		}

		if (userProvisioningApi.existsByEmail(request.email())) {
			throw new ConflictException("An account with this email already exists");
		}

		boolean approvalRequired = flag(config, "approval_required", false);
		ProvisionedUser provisioned = userProvisioningApi.provisionTenantUser(request.email(), request.password(),
				"STUDENT", false);
		if (approvalRequired) {
			userProvisioningApi.suspendTenantUser(provisioned.userId());
		}

		StudentProfile profile = new StudentProfile(tenantContext.getTenantId(), provisioned.userId(), request.name(),
				StudentRegistrationStatus.SELF_REGISTERED, null, null, null, null, null, null);
		StudentProfileFieldsService.applyRegistrationValues(profile, profileValues);
		profile = studentProfileRepository.save(profile);

		auditLogApi.record(AuditLogEntry.of(profile.getUserId(), "student.self_registered", "student_profile",
				profile.getId()));

		return new StudentAccount(profile.getId(), profile.getName(), provisioned.email(), "STUDENT",
				approvalRequired ? "SUSPENDED" : "ACTIVE");
	}

	private void requireRecentlyVerifiedOtp(String email) {
		StudentRegistrationOtp otp = otpRepository.findMostRecentByEmail(email).orElse(null);
		boolean verified = otp != null && otp.isConsumed()
				&& otp.getConsumedAt().isAfter(Instant.now().minus(VERIFIED_WINDOW_MINUTES, ChronoUnit.MINUTES));
		if (!verified) {
			throw new ConflictException("Email verification is required before registering");
		}
	}

	private void requirePublicRegistrationEnabled() {
		Map<String, Object> config = tenantConfigApi.resolveDomain(tenantContext.getTenantId(), ConfigDomain.STUDENT);
		if (!flag(config, "public_registration_enabled", true)) {
			throw new NotFoundException("Student self-registration is not available");
		}
	}

	/**
	 * {@code profileFields} merged with the six legacy top-level properties,
	 * which are mapped onto their catalogue keys only when {@code
	 * profileFields} does not name that key itself.
	 */
	private static Map<String, Object> registrationValues(StudentRegistrationRequest request) {
		Map<String, Object> values = new HashMap<>();
		if (request.profileFields() != null) {
			values.putAll(request.profileFields());
		}
		if ((request.guardianName() != null || request.guardianPhone() != null)
				&& !values.containsKey(StudentProfileField.GUARDIAN.name())) {
			Map<String, Object> guardian = new HashMap<>();
			guardian.put("name", request.guardianName());
			guardian.put("phone", request.guardianPhone());
			values.put(StudentProfileField.GUARDIAN.name(), guardian);
		}
		putLegacy(values, StudentProfileField.SCHOOL, request.school());
		putLegacy(values, StudentProfileField.GRADE, request.grade());
		putLegacy(values, StudentProfileField.AL_STREAM, request.stream());
		putLegacy(values, StudentProfileField.MOBILE, request.mobile());
		return values;
	}

	private static void putLegacy(Map<String, Object> values, StudentProfileField field, String value) {
		if (value != null) {
			values.putIfAbsent(field.name(), value);
		}
	}

	private static boolean flag(Map<String, Object> config, String key, boolean defaultValue) {
		Object value = config.get(key);
		return (value instanceof Boolean b) ? b : defaultValue;
	}

}
