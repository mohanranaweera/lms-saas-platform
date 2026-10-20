package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.api.LessonResourceTypeCatalog;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.service.CourseChargesInput;
import com.lms.coursemanagement.course.service.CourseCoverService;
import com.lms.coursemanagement.course.service.CourseEditCommand;
import com.lms.coursemanagement.course.service.CourseListFilter;
import com.lms.coursemanagement.course.service.CourseService;
import com.lms.coursemanagement.course.service.CourseSettingsInput;
import com.lms.coursemanagement.course.service.CourseView;
import com.lms.coursemanagement.course.service.NewCourseCommand;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseKlassRequests.CourseOrderRequest;
import com.lms.coursemanagement.course.web.dto.CourseKlassRequests.LessonResourceTypeResponse;
import com.lms.coursemanagement.course.web.dto.CourseKlassRequests.RegistrationRequest;
import com.lms.coursemanagement.course.web.dto.CoursePriceChangeRequest;
import com.lms.coursemanagement.course.web.dto.CoursePricingModelChangeRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.coursemanagement.course.web.dto.CourseTeacherReassignRequest;
import com.lms.coursemanagement.course.web.dto.CourseUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Course Management (MVP-008) course-level endpoints. Stays thin, delegates
 * entirely to {@link CourseService}/{@link CourseCoverService}, which perform
 * the real, combined staff-matrix-or-Teacher-ownership authorization check
 * per method. {@code @PreAuthorize("isAuthenticated()")} here is therefore a
 * coarse gate only, for every endpoint except teacher reassignment, which is
 * Tenant-Admin-only and checked directly.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

	private final CourseService courseService;

	private final CourseCoverService courseCoverService;

	public CourseController(CourseService courseService, CourseCoverService courseCoverService) {
		this.courseService = courseService;
		this.courseCoverService = courseCoverService;
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> createCourse(@Valid @RequestBody CourseCreateRequest request,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		NewCourseCommand command = new NewCourseCommand(request.name(), request.slug(), request.category(),
				request.subject(), request.stream(), request.grade(), request.academicYear(), request.description(),
				request.price(), request.accessDurationDays(), request.enrollmentRule(), request.status(),
				request.teacherId(),
				new CourseSettingsInput(request.teacherIds(), request.pricingModel(), request.enrollmentFee(),
						request.paymentValidDays(), request.commissionRate(), request.whatsappInviteUrl(),
						request.telegramInviteUrl(), request.tagNames(), request.linkedCourseIds()));
		CourseView view = courseService.createCourse(command, idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	/**
	 * Optional filters: {@code status}, {@code category}, {@code teacherId}
	 * (staff only), {@code includeArchived} (legacy), {@code archived}
	 * ({@code true} = archived tab, {@code false} = active tab; wins over
	 * {@code includeArchived}) and {@code search} (name or primary teacher
	 * name). Default order: {@code displayOrder} (nulls last), {@code
	 * createdAt}; an explicit {@code sort} param overrides it.
	 */
	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<CourseResponse>>> listCourses(
			@PageableDefault(size = 20) Pageable pageable, @RequestParam(required = false) CourseStatus status,
			@RequestParam(required = false) String category, @RequestParam(required = false) UUID teacherId,
			@RequestParam(required = false, defaultValue = "false") boolean includeArchived,
			@RequestParam(required = false) Boolean archived, @RequestParam(required = false) String search) {
		PageResponse<CourseView> page = courseService.listCourses(pageable,
				new CourseListFilter(status, category, teacherId, includeArchived, archived, search));
		PageResponse<CourseResponse> response = new PageResponse<>(
				page.content().stream().map(CourseController::toResponse).toList(), page.page(), page.size(),
				page.totalElements(), page.totalPages());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** KLASS-T4a: the Session "Resource Type" catalog (support level + help), any authenticated user. */
	@GetMapping("/lesson-resource-types")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<LessonResourceTypeResponse>>> lessonResourceTypes() {
		List<LessonResourceTypeResponse> types = LessonResourceTypeCatalog.all()
			.stream()
			.map(descriptor -> new LessonResourceTypeResponse(descriptor.type().name(), descriptor.label(),
					descriptor.support().name(), descriptor.help(), descriptor.liveClass()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(types));
	}

	/** KLASS-T4a: persists the admin list order of the given (tenant) course ids. */
	@PutMapping("/order")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> reorderCourses(@Valid @RequestBody CourseOrderRequest request) {
		courseService.reorderCourses(request.courseIds());
		return ResponseEntity.noContent().build();
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
				request.enrollmentRule(), request.accessDurationDays(), request.price(),
				new CourseSettingsInput(request.teacherIds(), request.pricingModel(), request.enrollmentFee(),
						request.paymentValidDays(), request.commissionRate(), request.whatsappInviteUrl(),
						request.telegramInviteUrl(), request.tagNames(), request.linkedCourseIds()),
				new CourseChargesInput(request.facilityFeeInherit(), request.facilityFeeOverride(),
						request.postageFee()));
		CourseView view = courseService.updateCourse(id, command);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	/** KLASS-T4a: "Enabled for Registration" switch (audited; never revokes existing access). */
	@PatchMapping("/{id}/registration")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> setRegistration(@PathVariable UUID id,
			@Valid @RequestBody RegistrationRequest request) {
		CourseView view = courseService.setRegistrationEnabled(id, request.enabled());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	/** KLASS-T4a: cover image upload - multipart {@code file}, JPEG/PNG/WebP (sniffed), at most 5 MB. */
	@PostMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> uploadCover(@PathVariable UUID id,
			@RequestPart("file") MultipartFile file) {
		courseCoverService.uploadCover(id, file);
		return ResponseEntity.ok(ApiResponse.success(toResponse(courseService.getCourse(id))));
	}

	/** KLASS-T4a: back to the default cover. */
	@DeleteMapping("/{id}/cover")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> removeCover(@PathVariable UUID id) {
		courseCoverService.removeCover(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(courseService.getCourse(id))));
	}

	/**
	 * Tenant Admin only - never {@code DomainArea.COURSES}'s flat matrix and
	 * never the Teacher-ownership path, per plan §10/§15(a).
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

	@PatchMapping("/{id}/pricing-model")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> changePricingModel(@PathVariable UUID id,
			@Valid @RequestBody CoursePricingModelChangeRequest request) {
		CourseView view = courseService.changePricingModel(id, request.pricingModel());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{id}/archive")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> archiveCourse(@PathVariable UUID id) {
		CourseView view = courseService.archiveCourse(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{id}/unarchive")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> unarchiveCourse(@PathVariable UUID id) {
		CourseView view = courseService.unarchiveCourse(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{id}/clone")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseResponse>> cloneCourse(@PathVariable UUID id) {
		CourseView view = courseService.cloneCourse(id);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	static CourseResponse toResponse(CourseView view) {
		return new CourseResponse(view.id(), view.teacherId(), view.name(), view.slug(), view.category(),
				view.subject(), view.stream(), view.grade(), view.academicYear(), view.description(), view.price(),
				view.accessDurationDays(), view.enrollmentRule(), view.status(), view.pricingModel(),
				view.archivedAt(), view.createdAt(), view.updatedAt(), view.resolvedAmount(), view.currency(),
				view.requiresManualQuote(), view.registrationEnabled(), view.enrollmentFee(), view.coverImageUrl(),
				view.whatsappInviteUrl(), view.telegramInviteUrl(), view.paymentValidDays(), view.commissionRate(),
				view.teachers()
					.stream()
					.map(t -> new CourseResponse.TeacherRef(t.id(), t.name(), t.primary()))
					.toList(),
				view.tags().stream().map(t -> new CourseResponse.TagRef(t.id(), t.name())).toList(),
				view.linkedCourses()
					.stream()
					.map(c -> new CourseResponse.LinkedCourseRef(c.id(), c.title()))
					.toList(),
				view.displayOrder(),
				view.priceDisplay() == null ? null
						: new CourseResponse.PriceDisplay(view.priceDisplay().amount(), view.priceDisplay().currency(),
								view.priceDisplay().period()),
				view.timezone(), view.facilityFeeOverride(), view.tenantDefaultFacilityFee(),
				view.facilityFeeBasis(), view.effectiveFacilityFee(), view.postageFee());
	}

}
