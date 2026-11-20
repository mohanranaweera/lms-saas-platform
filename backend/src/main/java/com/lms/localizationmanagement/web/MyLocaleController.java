package com.lms.localizationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.localizationmanagement.service.UserLocalePreferenceService;
import com.lms.localizationmanagement.web.dto.LocalePreferenceRequest;
import com.lms.localizationmanagement.web.dto.LocalePreferenceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/me/locale}: the signed-in user's own UI language for this tenant (doc section 6). */
@RestController
@RequestMapping("/api/v1/me/locale")
public class MyLocaleController {

	private final UserLocalePreferenceService preferenceService;

	public MyLocaleController(UserLocalePreferenceService preferenceService) {
		this.preferenceService = preferenceService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LocalePreferenceResponse>> get() {
		return ResponseEntity
			.ok(ApiResponse.success(new LocalePreferenceResponse(preferenceService.current().orElse(null))));
	}

	@PutMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LocalePreferenceResponse>> put(@RequestBody LocalePreferenceRequest request) {
		String locale = request == null ? null : request.locale();
		return ResponseEntity
			.ok(ApiResponse.success(new LocalePreferenceResponse(preferenceService.change(locale).orElse(null))));
	}

}
