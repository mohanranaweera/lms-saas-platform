package com.lms.identityaccessservice.service;

import com.lms.common.error.MobileLoginDisabledException;
import com.lms.common.error.TooManyRequestsException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.error.InvalidCredentialsException;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Tenant sign-in entry point (ADR-031): picks the identifier (email or mobile
 * number), applies the institute's mobile-sign-in switch and throttles failed
 * attempts, then delegates to the transactional {@link AuthenticationService}
 * (through its proxy, so no transaction is held across the Redis calls).
 *
 * <p>Throttling, both identifiers: per (tenant, identifier)
 * {@value #MAX_FAILURES_PER_IDENTIFIER} failures and per (tenant, client IP)
 * {@value #MAX_FAILURES_PER_IP} failures within {@link #WINDOW}; over either
 * limit the attempt is refused with {@code 429} before any password check. A
 * success clears the identifier's counter. {@link AuthRateLimiter} fails open
 * if Redis is unavailable, so an outage never locks users out.
 */
@Service
public class TenantLoginService {

	static final int MAX_FAILURES_PER_IDENTIFIER = 10;

	static final int MAX_FAILURES_PER_IP = 50;

	static final Duration WINDOW = Duration.ofMinutes(15);

	static final String TOO_MANY_MESSAGE = "Too many sign-in attempts. Please wait a few minutes and try again.";

	private final AuthenticationService authenticationService;

	private final LoginMobileService loginMobileService;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final AuthRateLimiter rateLimiter;

	public TenantLoginService(AuthenticationService authenticationService, LoginMobileService loginMobileService,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext, AuthRateLimiter rateLimiter) {
		this.authenticationService = authenticationService;
		this.loginMobileService = loginMobileService;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.rateLimiter = rateLimiter;
	}

	public LoginResult loginWithEmail(String email, String rawPassword, AuthenticationService.DeviceContext device) {
		String identifierKey = "email:" + email.strip().toLowerCase(Locale.ROOT);
		return throttled(identifierKey, device, () -> authenticationService.login(email, rawPassword, device));
	}

	public LoginResult loginWithMobile(String rawMobile, String rawPassword, AuthenticationService.DeviceContext device) {
		if (!tenantConfigApi.isMobileLoginEnabled(tenantContext.getTenantId())) {
			throw new MobileLoginDisabledException();
		}
		String e164 = loginMobileService.normalize(rawMobile).orElse(null);
		String identifierKey = "mobile:" + (e164 != null ? e164 : rawMobile.replaceAll("\\s+", ""));
		return throttled(identifierKey, device, () -> authenticationService.loginWithMobile(e164, rawPassword, device));
	}

	private LoginResult throttled(String identifier, AuthenticationService.DeviceContext device,
			Supplier<LoginResult> attempt) {
		String tenant = String.valueOf(tenantContext.getTenantId());
		String identifierKey = "login:id:" + tenant + ":" + identifier;
		String ipKey = "login:ip:" + tenant + ":" + device.ipAddress();
		if (rateLimiter.isAtLimit(identifierKey, MAX_FAILURES_PER_IDENTIFIER)
				|| rateLimiter.isAtLimit(ipKey, MAX_FAILURES_PER_IP)) {
			throw new TooManyRequestsException(TOO_MANY_MESSAGE);
		}
		try {
			LoginResult result = attempt.get();
			rateLimiter.reset(identifierKey);
			return result;
		}
		catch (InvalidCredentialsException ex) {
			rateLimiter.tryAcquire(identifierKey, MAX_FAILURES_PER_IDENTIFIER, WINDOW);
			rateLimiter.tryAcquire(ipKey, MAX_FAILURES_PER_IP, WINDOW);
			throw ex;
		}
	}

}
