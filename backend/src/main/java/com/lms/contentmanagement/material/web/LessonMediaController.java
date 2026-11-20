package com.lms.contentmanagement.material.web;

import com.lms.common.api.ApiResponse;
import com.lms.contentmanagement.material.service.LessonMediaService;
import com.lms.contentmanagement.material.web.dto.AttachLessonMediaRequest;
import com.lms.contentmanagement.material.web.dto.LessonMediaResponse;
import com.lms.contentmanagement.material.web.dto.UpdateLessonMediaRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LessonMedia-shaped facade over content-management's existing Material(VIDEO) rows. */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/lessons/{lessonId}/media")
public class LessonMediaController {

	private final LessonMediaService lessonMediaService;

	public LessonMediaController(LessonMediaService lessonMediaService) {
		this.lessonMediaService = lessonMediaService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<LessonMediaResponse>>> list(@PathVariable UUID courseId,
			@PathVariable UUID lessonId) {
		return ResponseEntity.ok(ApiResponse.success(lessonMediaService.list(courseId, lessonId)));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LessonMediaResponse>> attach(@PathVariable UUID courseId,
			@PathVariable UUID lessonId, @Valid @RequestBody AttachLessonMediaRequest request) {
		LessonMediaResponse response = lessonMediaService.attach(courseId, lessonId, request.mediaAssetId(),
				request.title(), request.sequence());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@PatchMapping("/{lessonMediaId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LessonMediaResponse>> update(@PathVariable UUID courseId,
			@PathVariable UUID lessonId, @PathVariable UUID lessonMediaId,
			@Valid @RequestBody UpdateLessonMediaRequest request) {
		LessonMediaResponse response = lessonMediaService.update(courseId, lessonId, lessonMediaId, request.title(),
				request.sequence(), request.visibility());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@DeleteMapping("/{lessonMediaId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> detach(@PathVariable UUID courseId, @PathVariable UUID lessonId,
			@PathVariable UUID lessonMediaId) {
		lessonMediaService.detach(courseId, lessonId, lessonMediaId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

}
