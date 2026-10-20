package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.service.CourseContentService;
import com.lms.enrollmentmanagement.service.CourseContentViews.CourseContent;
import com.lms.enrollmentmanagement.service.CourseContentViews.LessonLaunch;
import com.lms.enrollmentmanagement.service.CourseContentViews.LiveClassNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KLASS-T4b (plan §4.3): course content tree, live-class schedule and lesson
 * launch. Authorization (participant / staff preview, lesson evaluator) lives
 * in {@link CourseContentService}.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}")
public class CourseContentController {

	private final CourseContentService contentService;

	public CourseContentController(CourseContentService contentService) {
		this.contentService = contentService;
	}

	@GetMapping("/content")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseContent>> content(@PathVariable UUID courseId) {
		return ResponseEntity.ok(ApiResponse.success(contentService.getContent(courseId)));
	}

	@GetMapping("/live-classes")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<LiveClassNode>>> liveClasses(@PathVariable UUID courseId) {
		return ResponseEntity.ok(ApiResponse.success(contentService.liveClasses(courseId)));
	}

	/** 200 + records progress when ALLOWED; 403 {@code {code, access}} otherwise; 404 when UNAVAILABLE. */
	@PostMapping("/lessons/{lessonId}/launch")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<LessonLaunch>> launch(@PathVariable UUID courseId,
			@PathVariable UUID lessonId) {
		return ResponseEntity.ok(ApiResponse.success(contentService.launch(courseId, lessonId)));
	}

}
