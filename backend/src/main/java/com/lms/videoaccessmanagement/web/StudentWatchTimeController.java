package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.WatchTimeGrantService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff view of a Student's watch time and extra-time grants (VIDEO Phase 12),
 * next to the device and AOC Player endpoints under {@code
 * /api/v1/students/{studentId}}. {@code DomainArea.ACCESS_EXPIRY}: VIEW to
 * list, CREATE_EDIT to grant (audited, with a reason). Ids from another
 * tenant are 404.
 */
@RestController
public class StudentWatchTimeController {

	/** {@code POST .../grants} body. */
	public record GrantRequest(Integer extraMinutes, String reason) {
	}

	private final WatchTimeGrantService grantService;

	public StudentWatchTimeController(WatchTimeGrantService grantService) {
		this.grantService = grantService;
	}

	@GetMapping("/api/v1/students/{studentId}/watch-time")
	public ResponseEntity<ApiResponse<List<WatchTimeGrantService.VideoWatchTimeView>>> list(
			@PathVariable UUID studentId) {
		return ResponseEntity.ok(ApiResponse.success(grantService.list(studentId)));
	}

	@PostMapping("/api/v1/students/{studentId}/watch-time/{videoAssetId}/grants")
	public ResponseEntity<ApiResponse<WatchTimeGrantService.VideoWatchTimeView>> grant(@PathVariable UUID studentId,
			@PathVariable UUID videoAssetId, @RequestBody(required = false) GrantRequest request) {
		WatchTimeGrantService.VideoWatchTimeView view = grantService.grant(studentId, videoAssetId,
				request == null ? null : request.extraMinutes(), request == null ? null : request.reason());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(view));
	}

}
