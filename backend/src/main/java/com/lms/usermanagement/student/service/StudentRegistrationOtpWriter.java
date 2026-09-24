package com.lms.usermanagement.student.service;

import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.usermanagement.student.domain.StudentRegistrationOtp;
import com.lms.usermanagement.student.repository.StudentRegistrationOtpRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate, small collaborator bean (not a private/self-invoked method on
 * {@link StudentRegistrationService}) so its {@code @Transactional}
 * annotation actually takes effect through Spring's proxy - a private or
 * same-class-self-invoked method never goes through the AOP proxy, a classic
 * Spring pitfall. Mirrors this codebase's own established "persist in a
 * short, separate transaction, then make the outbound call after it commits"
 * precedent (see {@code PaymentWriteService#assignGatewayReference}'s
 * javadoc, referenced from {@code OrderService}), applied here to {@link
 * StudentRegistrationService#sendOtp}'s persist-then-email-send sequence.
 */
@Service
public class StudentRegistrationOtpWriter {

	private static final int OTP_LENGTH = 6;

	static final long OTP_TTL_MINUTES = 10;

	private static final int MAX_SENDS_PER_WINDOW = 3;

	private static final long RATE_LIMIT_WINDOW_MINUTES = 15;

	private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

	private final TenantContext tenantContext;

	private final StudentRegistrationOtpRepository otpRepository;

	private final PasswordEncoder passwordEncoder;

	public StudentRegistrationOtpWriter(TenantContext tenantContext, StudentRegistrationOtpRepository otpRepository,
			PasswordEncoder passwordEncoder) {
		this.tenantContext = tenantContext;
		this.otpRepository = otpRepository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * Rate-limit-checks and persists a new OTP row for {@code email},
	 * returning the RAW (unhashed) OTP value for the caller to email out -
	 * never itself persisted or logged in raw form.
	 * @throws ConflictException if {@code email} has already received {@code
	 * MAX_SENDS_PER_WINDOW} OTPs within the rate-limit window.
	 */
	@Transactional
	public String issueOtp(String email) {
		Instant now = Instant.now();
		Instant windowStart = now.minus(RATE_LIMIT_WINDOW_MINUTES, ChronoUnit.MINUTES);
		if (otpRepository.findAllByEmailCreatedSince(email, windowStart).size() >= MAX_SENDS_PER_WINDOW) {
			throw new ConflictException("Too many verification code requests - please try again later");
		}
		String rawOtp = generateOtp();
		UUID tenantId = tenantContext.getTenantId();
		StudentRegistrationOtp otp = new StudentRegistrationOtp(tenantId, email, passwordEncoder.encode(rawOtp),
				now.plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES));
		otpRepository.save(otp);
		return rawOtp;
	}

	private static String generateOtp() {
		int value = SECURE_RANDOM.nextInt(1_000_000);
		return String.format("%0" + OTP_LENGTH + "d", value);
	}

}
