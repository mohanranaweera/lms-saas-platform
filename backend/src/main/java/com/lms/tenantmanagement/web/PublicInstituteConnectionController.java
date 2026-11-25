package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.service.InstituteConnectionService;
import com.lms.tenantmanagement.service.InstituteConnectionService.PublicInstituteConnection;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INST-QR-01: resolves an Institute QR's connection code to allowlisted public
 * institute details and the approved sign-in destination. Anonymous, GET only,
 * excluded from {@code TenantResolutionFilter}: the tenant comes only from the code,
 * never from the Host header, and no redirect input is accepted. Returns no
 * credentials and creates no session - the caller still signs in normally.
 */
@RestController
@RequestMapping("/api/v1/public/institute-connections")
public class PublicInstituteConnectionController {

	private final InstituteConnectionService service;

	public PublicInstituteConnectionController(InstituteConnectionService service) {
		this.service = service;
	}

	@GetMapping("/{code}")
	public ResponseEntity<ApiResponse<PublicInstituteConnection>> resolve(@PathVariable String code) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.resolve(code)));
	}

}
