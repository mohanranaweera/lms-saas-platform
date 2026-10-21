package com.lms.identityaccessservice.web;

import com.lms.identityaccessservice.service.PasswordChangeService;
import com.lms.identityaccessservice.service.PasswordResetService;
import com.lms.identityaccessservice.service.PasswordTokenService;
import com.lms.identityaccessservice.web.dto.PasswordRequests.ChangePasswordRequest;
import com.lms.identityaccessservice.web.dto.PasswordRequests.CompleteTokenRequest;
import com.lms.identityaccessservice.web.dto.PasswordRequests.MessageResponse;
import com.lms.identityaccessservice.web.dto.PasswordRequests.PasswordResetRequest;
import com.lms.identityaccessservice.web.dto.PasswordRequests.TokenInfoResponse;
import com.lms.identityaccessservice.web.dto.PasswordRequests.TokenRequest;
import org.springframework.http.ResponseEntity;
import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.config.DeviceProperties;
import com.lms.identityaccessservice.error.InvalidRefreshTokenException;
import com.lms.identityaccessservice.service.AuthenticationService;
import com.lms.identityaccessservice.service.LoginResult;
import com.lms.identityaccessservice.service.RefreshTokenService;
import com.lms.identityaccessservice.service.RotationResult;
import com.lms.identityaccessservice.web.dto.LoginRequest;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.identityaccessservice.web.dto.RefreshResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped login/refresh/logout (AUTH-1/AUTH-2). Tenant identity is
 * never read from this controller or its request bodies - it is already
 * resolved in {@code TenantResolutionFilter} before any handler here runs.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	static final String REFRESH_COOKIE_NAME = "lms_refresh_token";

	static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

	/**
	 * Wave 10 (ADR-007 §2): the server-issued device identifier. Same path as
	 * the refresh cookie, so the browser sends it only to the auth endpoints.
	 */
	static final String DEVICE_COOKIE_NAME = "lms_device_id";

	private final AuthenticationService authenticationService;

	private final RefreshTokenService refreshTokenService;

	private final PasswordTokenService passwordTokenService;

	private final PasswordResetService passwordResetService;

	private final PasswordChangeService passwordChangeService;

	/**
	 * {@code Secure} is forced off only under the {@code local} profile - see
	 * {@link RefreshCookieSupport}'s javadoc. Resolved once at construction
	 * rather than per-request; profile membership never changes at runtime.
	 */
	private final boolean secureRefreshCookie;

	private final DeviceProperties deviceProperties;

	public AuthController(AuthenticationService authenticationService, RefreshTokenService refreshTokenService,
			PasswordTokenService passwordTokenService, PasswordResetService passwordResetService,
			PasswordChangeService passwordChangeService, Environment environment, DeviceProperties deviceProperties) {
		this.authenticationService = authenticationService;
		this.refreshTokenService = refreshTokenService;
		this.passwordTokenService = passwordTokenService;
		this.passwordResetService = passwordResetService;
		this.passwordChangeService = passwordChangeService;
		this.secureRefreshCookie = !environment.acceptsProfiles(Profiles.of("local"));
		this.deviceProperties = deviceProperties;
	}

	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
			@CookieValue(name = DEVICE_COOKIE_NAME, required = false) String deviceToken, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		var device = new AuthenticationService.DeviceContext(deviceToken, httpRequest.getHeader("User-Agent"),
				httpRequest.getRemoteAddr());
		LoginResult result = authenticationService.login(request.email(), request.password(), device);
		RefreshCookieSupport.set(httpResponse, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, result.rawRefreshToken(),
				result.refreshCookieMaxAge(), secureRefreshCookie);
		RefreshCookieSupport.set(httpResponse, DEVICE_COOKIE_NAME, REFRESH_COOKIE_PATH, result.rawDeviceToken(),
				deviceProperties.getCookieMaxAge(), secureRefreshCookie);
		return ApiResponse.success(new LoginResponse(result.accessToken(), result.expiresIn().toSeconds(),
				result.sessionId(), result.mustChangePassword()));
	}

	@PostMapping("/refresh")
	public ApiResponse<RefreshResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
			@CookieValue(name = DEVICE_COOKIE_NAME, required = false) String deviceToken, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRefreshTokenException();
		}
		RotationResult result = refreshTokenService.rotateTenantSession(refreshToken, deviceToken,
				httpRequest.getRemoteAddr());
		RefreshCookieSupport.set(httpResponse, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, result.rawRefreshToken(),
				result.refreshCookieMaxAge(), secureRefreshCookie);
		return ApiResponse.success(new RefreshResponse(result.accessToken(), result.expiresIn().toSeconds()));
	}

	/** AUTH-PWD-01: what an account-setup / reset link is for, before the password is entered. Public. */
	@PostMapping("/password-tokens/verify")
	public ApiResponse<TokenInfoResponse> verifyPasswordToken(@Valid @RequestBody TokenRequest request,
			HttpServletRequest httpRequest) {
		passwordTokenService.guardLinkAttempt(httpRequest.getRemoteAddr());
		PasswordTokenService.TokenInfo info = passwordTokenService.verify(request.token());
		return ApiResponse.success(new TokenInfoResponse(info.purpose().name(), info.emailMasked(), info.expiresAt()));
	}

	/** AUTH-PWD-01: set the password through a one-time link. Public. No automatic sign-in. */
	@PostMapping("/password-tokens/complete")
	public ResponseEntity<Void> completePasswordToken(@Valid @RequestBody CompleteTokenRequest request,
			HttpServletRequest httpRequest) {
		passwordTokenService.guardLinkAttempt(httpRequest.getRemoteAddr());
		passwordTokenService.complete(request.token(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	/** AUTH-PWD-01: forgot password. Public; always the same 202, whether or not the account exists. */
	@PostMapping("/password-reset/request")
	public ResponseEntity<ApiResponse<MessageResponse>> requestPasswordReset(
			@Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
		passwordResetService.requestReset(request.email(), httpRequest.getRemoteAddr());
		return ResponseEntity.accepted()
			.body(ApiResponse.success(new MessageResponse(
					"If an account exists for this email, we've sent a link to reset the password.")));
	}

	/** AUTH-PWD-01: change password while signed in; returns a fresh access token. */
	@PostMapping("/password")
	public ApiResponse<RefreshResponse> changePassword(@RequestBody ChangePasswordRequest request) {
		PasswordChangeService.ChangedPassword changed = passwordChangeService.change(request.currentPassword(),
				request.newPassword());
		return ApiResponse.success(new RefreshResponse(changed.accessToken(), changed.expiresIn().toSeconds()));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(HttpServletResponse httpResponse) {
		var principal = AuthenticatedPrincipalHolder.get();
		refreshTokenService.revokeTenantSession(principal.sessionId());
		RefreshCookieSupport.clear(httpResponse, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, secureRefreshCookie);
		return ApiResponse.success(null);
	}

}
