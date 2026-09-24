package com.lms.usermanagement.student.web;

import com.lms.common.api.ApiResponse;
import com.lms.usermanagement.student.service.StudentAccount;
import com.lms.usermanagement.student.service.StudentRegistrationService;
import com.lms.usermanagement.student.web.dto.OtpSendRequest;
import com.lms.usermanagement.student.web.dto.OtpVerifyRequest;
import com.lms.usermanagement.student.web.dto.StudentRegistrationRequest;
import com.lms.usermanagement.student.web.dto.StudentRegistrationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated student self-registration endpoints (Wave 3,
 * PAR-03-01 - replaces the disabled frontend placeholder). Tenant identity is
 * resolved exclusively from the request's subdomain by {@code
 * TenantResolutionFilter} (this controller is NOT excluded from that filter,
 * and NOT excluded from {@code TenantContext} resolution) - never from a
 * client-supplied tenant field, mirroring {@code
 * PublicBrandingController}/{@code TenantRegistrationController}'s
 * precedent. These three routes must be added to {@code
 * SecurityFilterChainConfig}'s {@code permitAll()} list.
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentRegistrationController {

	private final StudentRegistrationService studentRegistrationService;

	public StudentRegistrationController(StudentRegistrationService studentRegistrationService) {
		this.studentRegistrationService = studentRegistrationService;
	}

	@PostMapping("/register/otp/send")
	public ResponseEntity<ApiResponse<Void>> sendOtp(@Valid @RequestBody OtpSendRequest request) {
		studentRegistrationService.sendOtp(request.email());
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@PostMapping("/register/otp/verify")
	public ResponseEntity<ApiResponse<Void>> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
		studentRegistrationService.verifyOtp(request.email(), request.otp());
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@PostMapping("/register")
	public ResponseEntity<ApiResponse<StudentRegistrationResponse>> register(
			@Valid @RequestBody StudentRegistrationRequest request) {
		StudentAccount account = studentRegistrationService.register(request);
		boolean pendingApproval = "SUSPENDED".equals(account.status());
		StudentRegistrationResponse response = new StudentRegistrationResponse(account.id(), account.email(),
				pendingApproval);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

}
