package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.domain.StudentRegistrationOtp;
import com.lms.usermanagement.student.domain.StudentRegistrationStatus;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import com.lms.usermanagement.student.repository.StudentRegistrationOtpRepository;
import com.lms.usermanagement.student.web.dto.StudentRegistrationRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

	public StudentRegistrationService(TenantContext tenantContext, TenantConfigApi tenantConfigApi,
			StudentProfileRepository studentProfileRepository, StudentRegistrationOtpRepository otpRepository,
			StudentRegistrationOtpWriter otpWriter, UserProvisioningApi userProvisioningApi,
			MessagingProviderApi messagingProviderApi, PasswordEncoder passwordEncoder, AuditLogApi auditLogApi) {
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

		validateRequiredFields(request, config);

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
				StudentRegistrationStatus.SELF_REGISTERED, request.guardianName(), request.guardianPhone(),
				request.school(), request.grade(), request.stream(), request.mobile());
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

	private void validateRequiredFields(StudentRegistrationRequest request, Map<String, Object> config) {
		List<FieldError> errors = new ArrayList<>();
		if (flag(config, "require_guardian_info", false)) {
			if (isBlank(request.guardianName())) {
				errors.add(new FieldError("guardianName", "Guardian name is required"));
			}
			if (isBlank(request.guardianPhone())) {
				errors.add(new FieldError("guardianPhone", "Guardian phone is required"));
			}
		}
		if (flag(config, "require_school", false) && isBlank(request.school())) {
			errors.add(new FieldError("school", "School is required"));
		}
		if (flag(config, "require_grade", false) && isBlank(request.grade())) {
			errors.add(new FieldError("grade", "Grade is required"));
		}
		if (flag(config, "require_stream", false) && isBlank(request.stream())) {
			errors.add(new FieldError("stream", "Stream is required"));
		}
		if (flag(config, "require_mobile", false) && isBlank(request.mobile())) {
			errors.add(new FieldError("mobile", "Mobile number is required"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Registration is missing required fields for this institute", errors);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static boolean flag(Map<String, Object> config, String key, boolean defaultValue) {
		Object value = config.get(key);
		return (value instanceof Boolean b) ? b : defaultValue;
	}

}
