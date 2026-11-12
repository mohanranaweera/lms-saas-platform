package com.lms.contentmanagement.library.web;

import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.api.Capability;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.domain.MediaFileUsage;
import com.lms.contentmanagement.library.service.MediaFileService;
import com.lms.contentmanagement.library.service.MediaFileSummaryView;
import com.lms.contentmanagement.library.service.MediaFileView;
import com.lms.contentmanagement.library.web.dto.MediaFileMoveRequest;
import com.lms.contentmanagement.library.web.dto.MediaMemberResponse;
import com.lms.contentmanagement.library.web.dto.MediaFileOpenUrlResponse;
import com.lms.contentmanagement.library.web.dto.MediaFileRenameRequest;
import com.lms.contentmanagement.library.web.dto.MediaFileResponse;
import com.lms.contentmanagement.library.web.dto.MediaFileSummaryResponse;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Institute media file library - documents and images (Klass gaps round 2
 * G1). Staff only: students get 403. Thin: every authorization, tenant and
 * validation decision is made by {@link MediaFileService}.
 */
@RestController
@RequestMapping("/api/v1/media-files")
@Validated
public class MediaFileController {

	private final MediaFileService mediaFileService;

	public MediaFileController(MediaFileService mediaFileService) {
		this.mediaFileService = mediaFileService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_VIEW)
	public ResponseEntity<ApiResponse<PageResponse<MediaFileResponse>>> list(
			@RequestParam(name = "q", required = false) @Size(max = 200) String search,
			@RequestParam(required = false) MediaFileKind kind, @RequestParam(required = false) UUID folderId,
			@RequestParam(defaultValue = "false") boolean unfiled, @RequestParam(required = false) UUID uploadedBy,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "24") @Min(1) @Max(100) int size) {
		PageResponse<MediaFileResponse> body = PageResponse.from(mediaFileService
			.list(search, kind, folderId, unfiled, uploadedBy, page, size)
			.map(MediaFileController::toResponse));
		return ResponseEntity.ok(ApiResponse.success(body));
	}

	/** Uploader filter options over the files the caller may view in the folder scope (MEDIA-FOLDERS-01). */
	@GetMapping("/uploaders")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_VIEW)
	public ResponseEntity<ApiResponse<List<MediaMemberResponse>>> uploaders(
			@RequestParam(required = false) UUID folderId, @RequestParam(defaultValue = "false") boolean unfiled) {
		List<MediaMemberResponse> body = mediaFileService.uploaders(folderId, unfiled)
			.stream()
			.map(member -> new MediaMemberResponse(member.userId(), member.name()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(body));
	}

	@GetMapping("/summary")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_VIEW)
	public ResponseEntity<ApiResponse<MediaFileSummaryResponse>> summary() {
		MediaFileSummaryView view = mediaFileService.summary();
		return ResponseEntity.ok(ApiResponse
			.success(new MediaFileSummaryResponse(view.documents(), view.images(), view.other(), view.total())));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_ADD)
	public ResponseEntity<ApiResponse<MediaFileResponse>> upload(
			@RequestPart(value = "file", required = false) MultipartFile file,
			@RequestParam(value = "title", required = false) @Size(max = 255) String title,
			@RequestParam(value = "usage", required = false) MediaFileUsage usage,
			@RequestParam(value = "folderId", required = false) UUID folderId) {
		MediaFileView view = mediaFileService.upload(file, title, usage, folderId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaFileResponse>> rename(@PathVariable UUID id,
			@Valid @RequestBody MediaFileRenameRequest request) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaFileService.rename(id, request.title()))));
	}

	/** Files the file in a folder ({@code folderId} null = Unfiled). Same rights as rename (MEDIA-FOLDERS-01). */
	@PostMapping("/{id}/move")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaFileResponse>> move(@PathVariable UUID id,
			@RequestBody MediaFileMoveRequest request) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaFileService.move(id, request.folderId()))));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_DELETE)
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
		mediaFileService.delete(id);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@GetMapping("/{id}/open-url")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MEDIA_VIEW)
	public ResponseEntity<ApiResponse<MediaFileOpenUrlResponse>> openUrl(@PathVariable UUID id) {
		SignedDownloadUrl signed = mediaFileService.openUrl(id);
		return ResponseEntity.ok(ApiResponse.success(new MediaFileOpenUrlResponse(signed.url(), signed.expiresAt())));
	}

	private static MediaFileResponse toResponse(MediaFileView view) {
		return new MediaFileResponse(view.id(), view.kind(), view.title(), view.originalFilename(),
				view.contentType(), view.sizeBytes(), view.uploadedBy(), view.uploaderName(), view.createdAt(),
				view.attachedLessonCount(), view.thumbnailUrl(), view.canEdit(), view.canDelete(), view.folderId());
	}

}
