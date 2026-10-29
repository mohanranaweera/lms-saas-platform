package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.tenantmanagement.api.TenantAssetType;
import com.lms.tenantmanagement.domain.TenantAsset;
import com.lms.tenantmanagement.service.TenantAssetService;
import com.lms.tenantmanagement.web.dto.TenantAssetResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/tenant-assets")
public class TenantAssetController {
	private final TenantAssetService service;
	public TenantAssetController(TenantAssetService service) { this.service = service; }
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<TenantAssetResponse>> upload(@RequestParam TenantAssetType type,
			@RequestPart("file") MultipartFile file) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(service.upload(type, file))));
	}
	/** Live assets of one purpose (e.g. the hero banner gallery), oldest first, each with a short-lived preview URL. */
	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<List<TenantAssetResponse>>> list(@RequestParam TenantAssetType type) {
		return ResponseEntity.ok(ApiResponse.success(service.listLiveByType(type).stream().map(this::toResponse).toList()));
	}

	@GetMapping("/by-type/{type}")
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<TenantAssetResponse>> byType(@PathVariable TenantAssetType type) {
		try {
			TenantAsset asset = service.findLiveByType(type);
			return ResponseEntity.ok(ApiResponse.success(toResponse(asset)));
		}
		catch (com.lms.common.error.NotFoundException ex) {
			return ResponseEntity.notFound().build();
		}
	}

	@GetMapping("/{id}/preview-url")
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<TenantAssetResponse>> preview(@PathVariable UUID id) {
		SignedDownloadUrl url = service.preview(id);
		return ResponseEntity.ok(ApiResponse.success(new TenantAssetResponse(id, null, null, 0, null, url.url(), url.expiresAt())));
	}
	@DeleteMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.ok(ApiResponse.success(null)); }
	private TenantAssetResponse toResponse(TenantAsset asset) {
		SignedDownloadUrl url = service.preview(asset.getId());
		return new TenantAssetResponse(asset.getId(), asset.getAssetType(), asset.getContentType(), asset.getSizeBytes(),
			asset.getCreatedAt(), url.url(), url.expiresAt());
	}
}
