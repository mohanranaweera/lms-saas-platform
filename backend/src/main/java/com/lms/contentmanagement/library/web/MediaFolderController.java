package com.lms.contentmanagement.library.web;

import com.lms.common.api.ApiResponse;
import com.lms.contentmanagement.library.service.MediaFolderService;
import com.lms.contentmanagement.library.service.MediaFolderViews.Detail;
import com.lms.contentmanagement.library.service.MediaFolderViews.Folder;
import com.lms.contentmanagement.library.service.MediaFolderViews.Grant;
import com.lms.contentmanagement.library.web.dto.MediaFolderCreateRequest;
import com.lms.contentmanagement.library.web.dto.MediaFolderDetailResponse;
import com.lms.contentmanagement.library.web.dto.MediaFolderGrantRequest;
import com.lms.contentmanagement.library.web.dto.MediaFolderGrantResponse;
import com.lms.contentmanagement.library.web.dto.MediaFolderMoveRequest;
import com.lms.contentmanagement.library.web.dto.MediaFolderRenameRequest;
import com.lms.contentmanagement.library.web.dto.MediaFolderResponse;
import com.lms.contentmanagement.library.web.dto.MediaFolderTransferRequest;
import com.lms.contentmanagement.library.web.dto.MediaMemberResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media Library folders (MEDIA-FOLDERS-01 section 6). Staff only (students 403). Thin: tenant
 * (from the authenticated context only), authorization, validation and concurrency are all
 * decided by {@link MediaFolderService}. {@code @RequiresCapability} only gates / elevates the
 * configurable roles; the service re-checks every folder-level right.
 */
@RestController
@RequestMapping("/api/v1/media-folders")
@Validated
public class MediaFolderController {

	private final MediaFolderService folderService;

	public MediaFolderController(MediaFolderService folderService) {
		this.folderService = folderService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_VIEW)
	public ResponseEntity<ApiResponse<List<MediaFolderResponse>>> list(
			@RequestParam(required = false) UUID parentId) {
		return ResponseEntity.ok(ApiResponse
			.success(folderService.list(parentId).stream().map(MediaFolderController::toResponse).toList()));
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_VIEW)
	public ResponseEntity<ApiResponse<MediaFolderDetailResponse>> get(@PathVariable UUID id) {
		Detail detail = folderService.get(id);
		Folder f = detail.folder();
		List<MediaFolderDetailResponse.Crumb> crumbs = detail.breadcrumb()
			.stream()
			.map(crumb -> new MediaFolderDetailResponse.Crumb(crumb.id(), crumb.name()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(new MediaFolderDetailResponse(f.id(), f.name(), f.parentId(),
				f.ownerUserId(), f.ownerName(), f.access(), f.canManage(), f.canShare(), f.canTransfer(),
				f.canUpload(), f.childFolderCount(), f.fileCount(), f.version(), f.createdAt(), f.updatedAt(),
				crumbs)));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_MANAGE_FOLDERS)
	public ResponseEntity<ApiResponse<MediaFolderResponse>> create(@RequestBody MediaFolderCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(toResponse(folderService.create(request.name(), request.parentId()))));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_MANAGE_FOLDERS)
	public ResponseEntity<ApiResponse<MediaFolderResponse>> rename(@PathVariable UUID id,
			@RequestBody MediaFolderRenameRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(toResponse(folderService.rename(id, request.name(), request.expectedVersion()))));
	}

	@PostMapping("/{id}/move")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_MANAGE_FOLDERS)
	public ResponseEntity<ApiResponse<MediaFolderResponse>> move(@PathVariable UUID id,
			@RequestBody MediaFolderMoveRequest request) {
		return ResponseEntity.ok(
				ApiResponse.success(toResponse(folderService.move(id, request.parentId(), request.expectedVersion()))));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_MANAGE_FOLDERS)
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id,
			@RequestParam(required = false) Long expectedVersion) {
		folderService.delete(id, expectedVersion);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@GetMapping("/{id}/grants")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_SHARE_FOLDERS)
	public ResponseEntity<ApiResponse<List<MediaFolderGrantResponse>>> grants(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(toGrantResponses(folderService.listGrants(id))));
	}

	@PostMapping("/{id}/grants")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_SHARE_FOLDERS)
	public ResponseEntity<ApiResponse<List<MediaFolderGrantResponse>>> upsertGrants(@PathVariable UUID id,
			@Valid @RequestBody MediaFolderGrantRequest request) {
		return ResponseEntity.ok(ApiResponse
			.success(toGrantResponses(folderService.upsertGrants(id, request.userIds(), request.accessLevel()))));
	}

	@DeleteMapping("/{id}/grants/{userId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_SHARE_FOLDERS)
	public ResponseEntity<ApiResponse<List<MediaFolderGrantResponse>>> revokeGrant(@PathVariable UUID id,
			@PathVariable UUID userId) {
		return ResponseEntity.ok(ApiResponse.success(toGrantResponses(folderService.revokeGrant(id, userId))));
	}

	@PostMapping("/{id}/transfer")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_MANAGE_FOLDERS)
	public ResponseEntity<ApiResponse<MediaFolderResponse>> transfer(@PathVariable UUID id,
			@RequestBody MediaFolderTransferRequest request) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(folderService.transfer(id, request.newOwnerId(),
				request.formerOwnerAccess(), request.expectedVersion()))));
	}

	@GetMapping("/{id}/eligible-members")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_SHARE_FOLDERS)
	public ResponseEntity<ApiResponse<List<MediaMemberResponse>>> eligibleMembers(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(folderService.eligibleMembers(id)
			.stream()
			.map(member -> new MediaMemberResponse(member.userId(), member.name()))
			.toList()));
	}

	private static MediaFolderResponse toResponse(Folder f) {
		return new MediaFolderResponse(f.id(), f.name(), f.parentId(), f.ownerUserId(), f.ownerName(), f.access(),
				f.canManage(), f.canShare(), f.canTransfer(), f.canUpload(), f.childFolderCount(), f.fileCount(),
				f.version(), f.createdAt(), f.updatedAt());
	}

	private static List<MediaFolderGrantResponse> toGrantResponses(List<Grant> grants) {
		return grants.stream()
			.map(grant -> new MediaFolderGrantResponse(grant.userId(), grant.name(), grant.accessLevel(),
					grant.grantedAt()))
			.toList();
	}

}
