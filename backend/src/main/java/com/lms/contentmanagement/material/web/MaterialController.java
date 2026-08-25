package com.lms.contentmanagement.material.web;

import com.lms.common.api.ApiResponse;
import com.lms.contentmanagement.material.service.MaterialService;
import com.lms.contentmanagement.material.service.MaterialView;
import com.lms.contentmanagement.material.web.dto.MaterialDownloadUrlResponse;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.contentmanagement.material.web.dto.MaterialUpdateRequest;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Material endpoints, nested under a course/module/lesson (content-management,
 * MVP-009). Stays thin, delegates entirely to {@link MaterialService}, which
 * performs the real authorization check via {@code MaterialAccessGuard} -
 * mirrors {@code coursemanagement}'s {@code CourseLessonController} style
 * exactly.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials")
@Validated
public class MaterialController {

	private final MaterialService materialService;

	public MaterialController(MaterialService materialService) {
		this.materialService = materialService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<MaterialResponse>>> listMaterials(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @PathVariable UUID lessonId) {
		List<MaterialResponse> materials = materialService.listMaterials(courseId, moduleId, lessonId)
			.stream()
			.map(MaterialController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(materials));
	}

	@GetMapping("/{materialId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MaterialResponse>> getMaterial(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @PathVariable UUID lessonId, @PathVariable UUID materialId) {
		MaterialView view = materialService.getMaterial(courseId, moduleId, lessonId, materialId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{materialId}/download-url")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MaterialDownloadUrlResponse>> getDownloadUrl(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @PathVariable UUID lessonId, @PathVariable UUID materialId) {
		SignedDownloadUrl signed = materialService.getDownloadUrl(courseId, moduleId, lessonId, materialId);
		return ResponseEntity
			.ok(ApiResponse.success(new MaterialDownloadUrlResponse(signed.url(), signed.expiresAt())));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MaterialResponse>> createMaterial(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @PathVariable UUID lessonId, @RequestPart("file") MultipartFile file,
			@RequestParam("title") @NotBlank @Size(max = 255) String title) {
		MaterialView view = materialService.createMaterial(courseId, moduleId, lessonId, title, file);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{materialId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MaterialResponse>> updateMaterial(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @PathVariable UUID lessonId, @PathVariable UUID materialId,
			@Valid @RequestBody MaterialUpdateRequest request) {
		MaterialView view = materialService.updateMaterial(courseId, moduleId, lessonId, materialId, request.title(),
				request.sequence(), request.visibility());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@DeleteMapping("/{materialId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> deleteMaterial(@PathVariable UUID courseId, @PathVariable UUID moduleId,
			@PathVariable UUID lessonId, @PathVariable UUID materialId) {
		materialService.deleteMaterial(courseId, moduleId, lessonId, materialId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static MaterialResponse toResponse(MaterialView view) {
		return new MaterialResponse(view.id(), view.lessonId(), view.title(), view.originalFilename(),
				view.mimeType(), view.sizeBytes(), view.sequence(), view.visibility(), view.expiryAt(),
				view.uploadedBy(), view.createdAt(), view.updatedAt());
	}

}
