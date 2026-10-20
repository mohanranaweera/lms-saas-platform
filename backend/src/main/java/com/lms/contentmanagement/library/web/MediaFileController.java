package com.lms.contentmanagement.library.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.service.MediaFileService;
import com.lms.contentmanagement.library.service.MediaFileSummaryView;
import com.lms.contentmanagement.library.service.MediaFileView;
import com.lms.contentmanagement.library.web.dto.MediaFileOpenUrlResponse;
import com.lms.contentmanagement.library.web.dto.MediaFileRenameRequest;
import com.lms.contentmanagement.library.web.dto.MediaFileResponse;
import com.lms.contentmanagement.library.web.dto.MediaFileSummaryResponse;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
	public ResponseEntity<ApiResponse<PageResponse<MediaFileResponse>>> list(
			@RequestParam(name = "q", required = false) @Size(max = 200) String search,
			@RequestParam(required = false) MediaFileKind kind, @RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "24") @Min(1) @Max(100) int size) {
		PageResponse<MediaFileResponse> body = PageResponse
			.from(mediaFileService.list(search, kind, page, size).map(MediaFileController::toResponse));
		return ResponseEntity.ok(ApiResponse.success(body));
	}

	@GetMapping("/summary")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaFileSummaryResponse>> summary() {
		MediaFileSummaryView view = mediaFileService.summary();
		return ResponseEntity.ok(ApiResponse
			.success(new MediaFileSummaryResponse(view.documents(), view.images(), view.other(), view.total())));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaFileResponse>> upload(
			@RequestPart(value = "file", required = false) MultipartFile file,
			@RequestParam(value = "title", required = false) @Size(max = 255) String title) {
		MediaFileView view = mediaFileService.upload(file, title);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaFileResponse>> rename(@PathVariable UUID id,
			@Valid @RequestBody MediaFileRenameRequest request) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaFileService.rename(id, request.title()))));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
		mediaFileService.delete(id);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@GetMapping("/{id}/open-url")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaFileOpenUrlResponse>> openUrl(@PathVariable UUID id) {
		SignedDownloadUrl signed = mediaFileService.openUrl(id);
		return ResponseEntity.ok(ApiResponse.success(new MediaFileOpenUrlResponse(signed.url(), signed.expiresAt())));
	}

	private static MediaFileResponse toResponse(MediaFileView view) {
		return new MediaFileResponse(view.id(), view.kind(), view.title(), view.originalFilename(),
				view.contentType(), view.sizeBytes(), view.uploadedBy(), view.uploaderName(), view.createdAt(),
				view.attachedLessonCount(), view.thumbnailUrl(), view.canEdit(), view.canDelete());
	}

}
