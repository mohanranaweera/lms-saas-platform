package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.service.CourseEditCommand;
import com.lms.coursemanagement.course.service.CourseListFilter;
import com.lms.coursemanagement.course.service.CourseService;
import com.lms.coursemanagement.course.service.CourseView;
import com.lms.coursemanagement.course.service.NewCourseCommand;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CoursePriceChangeRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.coursemanagement.course.web.dto.CourseTeacherReassignRequest;
import com.lms.coursemanagement.course.web.dto.CourseUpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Course Management (MVP-008) course-level endpoints. Stays thin, delegates
 * entirely to {@link CourseService}, which performs the real, combined
 * staff-matrix-or-Teacher-ownership authorization check per method (a check
 * that cannot be expressed cleanly as a simple {@code @PreAuthorize} SpEL
 * expression without loading the entity first anyway - see {@code
 * CourseAccessGuard}). {@code @PreAuthorize("isAuthenticated()")} here is
 * therefore a coarse gate only, for every endpoint except teacher
 * reassignment, which is Tenant-Admin-only and checked directly.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

	private final CourseService courseService;

	public CourseController(CourseService courseService) {
		this.courseService = courseService;
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> createCourse(@Valid @RequestBody CourseCreateRequest request) {
		NewCourseCommand command = new NewCourseCommand(request.name(), request.slug(), request.category(),
				request.subject(), request.stream(), request.grade(), request.academicYear(), request.description(),
				request.price(), request.accessDurationDays(), request.enrollmentRule(), request.status(),
				request.teacherId());
		CourseView view = courseService.createCourse(command);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	/**
	 * {@code status}/{@code category}/{@code teacherId} are all optional
	 * query-param filters - see {@link CourseService#listCourses}'s javadoc
	 * for the Teacher-role-vs-staff authorization/filtering split (a
	 * Teacher-role caller's own {@code teacherId} is always enforced
	 * server-side; the {@code teacherId} query param here is only ever
	 * honored for a staff caller).
	 */
	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<CourseResponse>>> listCourses(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) CourseStatus status, @RequestParam(required = false) String category,
			@RequestParam(required = false) UUID teacherId) {
		PageResponse<CourseView> page = courseService.listCourses(pageable,
				new CourseListFilter(status, category, teacherId));
		PageResponse<CourseResponse> response = new PageResponse<>(
				page.content().stream().map(CourseController::toResponse).toList(), page.page(), page.size(),
				page.totalElements(), page.totalPages());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> getCourse(@PathVariable UUID id) {
		CourseView view = courseService.getCourse(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> updateCourse(@PathVariable UUID id,
			@Valid @RequestBody CourseUpdateRequest request) {
		CourseEditCommand command = new CourseEditCommand(request.name(), request.slug(), request.category(),
				request.subject(), request.stream(), request.grade(), request.academicYear(), request.description(),
				request.enrollmentRule(), request.accessDurationDays());
		CourseView view = courseService.updateCourse(id, command);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	/**
	 * Tenant Admin only - never {@code DomainArea.COURSES}'s flat matrix and
	 * never the Teacher-ownership path, per plan §10/§15(a). {@code
	 * hasRole('TENANT_ADMIN')} matches {@code JwtAuthenticationFilter}'s
	 * {@code "ROLE_" + principal.role()} granted-authority construction,
	 * where {@code principal.role()} is the live {@code Role} enum name
	 * (e.g. {@code "TENANT_ADMIN"}).
	 */
	@PostMapping("/{id}/teacher")
	@PreAuthorize("hasRole('TENANT_ADMIN')")
	public ResponseEntity<ApiResponse<CourseResponse>> reassignTeacher(@PathVariable UUID id,
			@Valid @RequestBody CourseTeacherReassignRequest request) {
		CourseView view = courseService.reassignTeacher(id, request.teacherId());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{id}/price")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> changePrice(@PathVariable UUID id,
			@Valid @RequestBody CoursePriceChangeRequest request) {
		CourseView view = courseService.changePrice(id, request.price());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{id}/publish")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> publish(@PathVariable UUID id) {
		CourseView view = courseService.publish(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{id}/unpublish")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> unpublish(@PathVariable UUID id) {
		CourseView view = courseService.unpublish(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> deleteCourse(@PathVariable UUID id) {
		courseService.deleteCourse(id);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static CourseResponse toResponse(CourseView view) {
		return new CourseResponse(view.id(), view.teacherId(), view.name(), view.slug(), view.category(),
				view.subject(), view.stream(), view.grade(), view.academicYear(), view.description(), view.price(),
				view.accessDurationDays(), view.enrollmentRule(), view.status(), view.createdAt(), view.updatedAt());
	}

}
