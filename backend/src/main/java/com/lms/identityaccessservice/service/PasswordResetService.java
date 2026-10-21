package com.lms.identityaccessservice.service;

import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.PasswordEmailRequestedEvent;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot password (AUTH-PWD-01 / ADR-020). The caller always gets the same
 * answer - this method returns nothing - whether or not the email belongs to
 * an account, so it can't be used to discover accounts.
 *
 * <ul>
 * <li>Only this tenant's accounts are looked up (tenant from the host).</li>
 * <li>{@code active} → reset email; {@code invited} → a fresh account-setup
 * email; {@code suspended} or unknown → nothing.</li>
 * <li>Rate limits per IP and per email; over the limit nothing is sent and
 * a security-log entry is written.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	static final int PER_IP_LIMIT = 5;

	static final int PER_EMAIL_LIMIT = 3;

	static final Duration WINDOW = Duration.ofHours(1);

	private final TenantUserRepository users;

	private final AuthRateLimiter rateLimiter;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantContext tenantContext;

	public PasswordResetService(TenantUserRepository users, AuthRateLimiter rateLimiter,
			ApplicationEventPublisher eventPublisher, TenantContext tenantContext) {
		this.users = users;
		this.rateLimiter = rateLimiter;
		this.eventPublisher = eventPublisher;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public void requestReset(String email, String clientIp) {
		UUID tenantId = tenantContext.getTenantId();
		String normalised = (email == null) ? "" : email.trim().toLowerCase(Locale.ROOT);
		boolean ipOk = rateLimiter.tryAcquire("reset:ip:" + tenantId + ":" + clientIp, PER_IP_LIMIT, WINDOW);
		boolean emailOk = rateLimiter.tryAcquire("reset:email:" + tenantId + ":" + normalised, PER_EMAIL_LIMIT, WINDOW);
		if (!ipOk || !emailOk) {
			securityLog.atWarn()
				.setMessage("auth.password_reset_rate_limited")
				.addKeyValue("tenantId", tenantId)
				.addKeyValue("limit", ipOk ? "email" : "ip")
				.log();
			return;
		}
		Optional<TenantUser> user = users.findByEmail(normalised);
		if (user.isEmpty()) {
			return;
		}
		AccountStatus status = user.get().getStatus();
		PasswordTokenPurpose purpose = switch (status) {
			case ACTIVE -> PasswordTokenPurpose.PASSWORD_RESET;
			case INVITED -> PasswordTokenPurpose.ACCOUNT_SETUP;
			case SUSPENDED -> null;
		};
		if (purpose != null) {
			eventPublisher.publishEvent(new PasswordEmailRequestedEvent(tenantId, user.get().getId(), purpose));
		}
	}

}
