package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.error.InvalidRefreshTokenException;
import com.lms.identityaccessservice.service.LoginResult;
import com.lms.identityaccessservice.service.PlatformAdminAuthenticationService;
import com.lms.identityaccessservice.service.RefreshTokenService;
import com.lms.identityaccessservice.service.RotationResult;
import com.lms.identityaccessservice.web.dto.LoginRequest;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.identityaccessservice.web.dto.RefreshResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Admin login/refresh/logout - structurally separate path (plan
 * §9/§15), never a role branch inside {@link AuthController}. {@code
 * TenantResolutionFilter} excludes this entire path prefix, so no tenant
 * resolution ever runs here.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/auth")
public class PlatformAdminAuthController {

	static final String REFRESH_COOKIE_NAME = "lms_platform_admin_refresh_token";

	static final String REFRESH_COOKIE_PATH = "/api/v1/platform-admin/auth";

	private final PlatformAdminAuthenticationService platformAdminAuthenticationService;

	private final RefreshTokenService refreshTokenService;

	public PlatformAdminAuthController(PlatformAdminAuthenticationService platformAdminAuthenticationService,
			RefreshTokenService refreshTokenService) {
		this.platformAdminAuthenticationService = platformAdminAuthenticationService;
		this.refreshTokenService = refreshTokenService;
	}

	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		String deviceIdentifierHash = DeviceFingerprint.hash(httpRequest);
		LoginResult result = platformAdminAuthenticationService.login(request.email(), request.password(),
				deviceIdentifierHash);
		RefreshCookieSupport.set(httpResponse, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, result.rawRefreshToken(),
				result.refreshCookieMaxAge());
		return ApiResponse.success(new LoginResponse(result.accessToken(), result.expiresIn().toSeconds(),
				result.sessionId(), result.mustChangePassword()));
	}

	@PostMapping("/refresh")
	public ApiResponse<RefreshResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
			HttpServletResponse httpResponse) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRefreshTokenException();
		}
		RotationResult result = refreshTokenService.rotatePlatformAdminSession(refreshToken);
		RefreshCookieSupport.set(httpResponse, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, result.rawRefreshToken(),
				result.refreshCookieMaxAge());
		return ApiResponse.success(new RefreshResponse(result.accessToken(), result.expiresIn().toSeconds()));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(HttpServletResponse httpResponse) {
		var principal = AuthenticatedPrincipalHolder.get();
		refreshTokenService.revokePlatformAdminSession(principal.sessionId());
		RefreshCookieSupport.clear(httpResponse, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH);
		return ApiResponse.success(null);
	}

}
