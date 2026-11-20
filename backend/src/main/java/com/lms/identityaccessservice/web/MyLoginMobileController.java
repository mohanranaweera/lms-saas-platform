package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.LoginMobileRequest;
import com.lms.identityaccessservice.api.LoginMobileResponse;
import com.lms.identityaccessservice.service.LoginMobileService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-031: the signed-in institute user's own sign-in mobile (so a Tenant
 * Admin, who isn't in the staff list, can set one). Always the caller's own
 * account - there is no user id in the request. Students are refused: their
 * sign-in mobile is their profile mobile, changed on their profile. Platform
 * Admin sign-in stays email-only.
 */
@RestController
@RequestMapping("/api/v1/auth/me/login-mobile")
@PreAuthorize("isAuthenticated()")
public class MyLoginMobileController {

	private final LoginMobileService loginMobileService;

	public MyLoginMobileController(LoginMobileService loginMobileService) {
		this.loginMobileService = loginMobileService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<LoginMobileResponse>> get() {
		UUID userId = requireInstituteStaffOrTeacher();
		return ResponseEntity
			.ok(ApiResponse.success(new LoginMobileResponse(loginMobileService.find(List.of(userId)).get(userId))));
	}

	@PutMapping
	public ResponseEntity<ApiResponse<LoginMobileResponse>> set(@Valid @RequestBody LoginMobileRequest request) {
		UUID userId = requireInstituteStaffOrTeacher();
		loginMobileService.set(userId, request.loginMobile(), "loginMobile");
		return ResponseEntity
			.ok(ApiResponse.success(new LoginMobileResponse(loginMobileService.find(List.of(userId)).get(userId))));
	}

	private static UUID requireInstituteStaffOrTeacher() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (principal.tenantId() == null || "STUDENT".equals(principal.role())
				|| "PLATFORM_ADMIN".equals(principal.role())) {
			throw new AccessDeniedException("Students change their mobile number on their profile");
		}
		return principal.userId();
	}

}
