package com.lms.liveclassmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.ShortLivedJoinLink;
import com.lms.integrationmanagement.api.ShortLivedPlaybackLink;
import com.lms.liveclassmanagement.domain.ClassSessionStatus;
import com.lms.liveclassmanagement.service.ClassSessionListFilter;
import com.lms.liveclassmanagement.service.ClassSessionSchedulingService;
import com.lms.liveclassmanagement.service.ClassSessionService;
import com.lms.liveclassmanagement.service.ClassSessionView;
import com.lms.liveclassmanagement.service.EditSessionCommand;
import com.lms.liveclassmanagement.service.NewSessionCommand;
import com.lms.liveclassmanagement.web.dto.ClassSessionCreateRequest;
import com.lms.liveclassmanagement.web.dto.ClassSessionJoinResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionRecordingResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionUpdateRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 4 (PAR-19-01..05) {@code class-session} endpoints. Stays thin,
 * delegates entirely to {@link ClassSessionSchedulingService} (schedule/
 * retry-provisioning, the two-phase provider-call flow) and {@link
 * ClassSessionService} (everything else) - both perform the real, combined
 * staff-matrix-or-Teacher-ownership-or-Student-entitlement authorization
 * check per method via {@code LiveClassAccessGuard}, mirroring {@code
 * CourseController}'s identical "{@code @PreAuthorize("isAuthenticated()")}
 * is a coarse gate only" shape.
 */
@RestController
@RequestMapping("/api/v1/class-sessions")
public class ClassSessionController {

	private final ClassSessionSchedulingService schedulingService;

	private final ClassSessionService classSessionService;

	public ClassSessionController(ClassSessionSchedulingService schedulingService,
			ClassSessionService classSessionService) {
		this.schedulingService = schedulingService;
		this.classSessionService = classSessionService;
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> scheduleSession(
			@Valid @RequestBody ClassSessionCreateRequest request) {
		NewSessionCommand command = new NewSessionCommand(request.courseId(), request.lessonId(), request.title(),
				request.description(), request.scheduledStart(), request.scheduledEnd());
		ClassSessionView view = schedulingService.scheduleSession(command);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(ClassSessionResponse.from(view)));
	}

	@PostMapping("/{id}/retry-provisioning")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> retryProvisioning(@PathVariable UUID id) {
		ClassSessionView view = schedulingService.retryProvisioning(id);
		return ResponseEntity.ok(ApiResponse.success(ClassSessionResponse.from(view)));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> updateSession(@PathVariable UUID id,
			@Valid @RequestBody ClassSessionUpdateRequest request) {
		EditSessionCommand command = new EditSessionCommand(request.title(), request.description(),
				request.scheduledStart(), request.scheduledEnd());
		ClassSessionView view = classSessionService.updateSession(id, command);
		return ResponseEntity.ok(ApiResponse.success(ClassSessionResponse.from(view)));
	}

	@PostMapping("/{id}/start")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> startSession(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(ClassSessionResponse.from(classSessionService.startSession(id))));
	}

	@PostMapping("/{id}/complete")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> completeSession(@PathVariable UUID id) {
		return ResponseEntity
			.ok(ApiResponse.success(ClassSessionResponse.from(classSessionService.completeSession(id))));
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> cancelSession(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(ClassSessionResponse.from(classSessionService.cancelSession(id))));
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<ClassSessionResponse>>> listSessions(
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) ClassSessionStatus status,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		List<ClassSessionResponse> responses = classSessionService
			.listSessions(new ClassSessionListFilter(courseId, status, from, to))
			.stream()
			.map(ClassSessionResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(responses));
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionResponse>> getSession(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(ClassSessionResponse.from(classSessionService.getSession(id))));
	}

	@PostMapping("/{id}/join")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionJoinResponse>> join(@PathVariable UUID id) {
		ShortLivedJoinLink link = classSessionService.join(id);
		return ResponseEntity.ok(ApiResponse.success(new ClassSessionJoinResponse(link.url(), link.expiresAt())));
	}

	@GetMapping("/{id}/recording")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionRecordingResponse>> getRecording(@PathVariable UUID id) {
		ShortLivedPlaybackLink link = classSessionService.getRecordingPlaybackUrl(id);
		return ResponseEntity.ok(ApiResponse.success(new ClassSessionRecordingResponse(link.url(), link.expiresAt())));
	}

}
