package com.lms.contentmanagement.material.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.service.MaterialCreateCommand;
import com.lms.contentmanagement.material.service.MaterialService;
import com.lms.contentmanagement.material.service.MaterialView;
import com.lms.contentmanagement.material.web.dto.MaterialDownloadUrlResponse;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.contentmanagement.material.web.dto.MaterialUpdateRequest;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 * MVP-009, extended Wave 5 with the {@code materialType}-discriminated create
 * request - plan §3/§4). Stays thin, delegates entirely to {@link
 * MaterialService}, which performs the real authorization AND per-type field
 * validation via {@code MaterialAccessGuard}/{@code
 * MaterialService#createMaterial} - mirrors {@code coursemanagement}'s {@code
 * CourseLessonController} style exactly.
 *
 * <p>{@code file} is now optional at the HTTP layer ({@code required =
 * false}) since only an uploaded-file material type ({@code PDF}/{@code
 * IMAGE}/{@code DOCUMENT}/{@code OTHER}) needs one - {@code LINK}/{@code
 * NOTE}/{@code VIDEO}/{@code RECORDING} materials are submitted as plain
 * multipart form fields with no file part at all. {@code availableFromAt}/
 * {@code expiryAt} are accepted as raw ISO-8601 strings (parsed here, not by
 * relying on an unverified {@code Instant} request-parameter {@code
 * Converter}) - a malformed timestamp is rejected as a clean {@link
 * FieldValidationException}/400, the same shape every other manual-validation
 * 400 in this codebase already uses (see {@link FieldValidationException}'s
 * javadoc), never a raw parse-exception 500.
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
			@PathVariable UUID moduleId, @PathVariable UUID lessonId,
			@RequestPart(value = "file", required = false) MultipartFile file,
			@RequestParam("title") @NotBlank @Size(max = 255) String title,
			@RequestParam(value = "materialType", required = false) MaterialType materialType,
			@RequestParam(value = "externalUrl", required = false) String externalUrl,
			@RequestParam(value = "noteContent", required = false) String noteContent,
			@RequestParam(value = "videoAssetId", required = false) UUID videoAssetId,
			@RequestParam(value = "sessionId", required = false) UUID sessionId,
			@RequestParam(value = "maxDownloads", required = false) Integer maxDownloads,
			@RequestParam(value = "availableFromAt", required = false) String availableFromAt,
			@RequestParam(value = "expiryAt", required = false) String expiryAt) {
		MaterialCreateCommand command = new MaterialCreateCommand(courseId, moduleId, lessonId, title, materialType,
				file, externalUrl, noteContent, videoAssetId, sessionId, maxDownloads,
				parseInstant("availableFromAt", availableFromAt), parseInstant("expiryAt", expiryAt));
		MaterialView view = materialService.createMaterial(command);
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

	/**
	 * Basic request-shape conversion (not business validation - see class
	 * javadoc): {@code null}/blank passes through as {@code null} (field
	 * omitted); a non-blank value that isn't a valid ISO-8601 instant (e.g.
	 * {@code "2024-01-01T00:00:00Z"}) is rejected as a clean 400 rather than
	 * an unhandled {@link DateTimeParseException} falling through to {@code
	 * GlobalExceptionHandler}'s generic 500.
	 */
	private static Instant parseInstant(String fieldName, String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(rawValue);
		}
		catch (DateTimeParseException e) {
			throw new FieldValidationException("Request parameter '" + fieldName + "' is not a valid timestamp",
					List.of(new FieldError(fieldName, "must be an ISO-8601 instant, e.g. 2024-01-01T00:00:00Z")));
		}
	}

	private static MaterialResponse toResponse(MaterialView view) {
		return new MaterialResponse(view.id(), view.lessonId(), view.sessionId(), view.materialType(), view.title(),
				view.originalFilename(), view.mimeType(), view.sizeBytes(), view.externalUrl(), view.noteContent(),
				view.videoAssetId(), view.sequence(), view.visibility(), view.maxDownloads(), view.downloadCount(),
				view.availableFromAt(), view.expiryAt(), view.uploadedBy(), view.createdAt(), view.updatedAt());
	}

}
