package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.TenantVideoChannelService;
import com.lms.videoaccessmanagement.service.TenantVideoChannelService.ChannelSettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin → Video providers → the institute YouTube channel's name,
 * description and allowed teachers (VIDEO Phase 5b). Permission checks and
 * tenant scoping live in {@link TenantVideoChannelService}.
 */
@RestController
@RequestMapping("/api/v1/tenant-admin/video-providers/youtube/settings")
@PreAuthorize("isAuthenticated()")
public class TenantVideoChannelController {

	private final TenantVideoChannelService service;

	public TenantVideoChannelController(TenantVideoChannelService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<ChannelSettingsView>> get() {
		return ResponseEntity.ok(ApiResponse.success(service.get()));
	}

	@PutMapping
	public ResponseEntity<ApiResponse<ChannelSettingsView>> update(@Valid @RequestBody ChannelSettingsRequest request) {
		return ResponseEntity.ok(ApiResponse
			.success(service.update(request.displayName(), request.description(), request.allowedTeacherIds())));
	}

	/** {@code allowedTeacherIds} empty (or absent) = every teacher may upload. */
	public record ChannelSettingsRequest(@Size(max = 200) String displayName, @Size(max = 1000) String description,
			@Size(max = 500) List<UUID> allowedTeacherIds) {

	}

}
