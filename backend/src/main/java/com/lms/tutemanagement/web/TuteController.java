package com.lms.tutemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.tutemanagement.service.TuteFulfillmentService;
import com.lms.tutemanagement.service.TuteInputValidator.TuteInput;
import com.lms.tutemanagement.service.TuteService;
import com.lms.tutemanagement.service.TuteViews.AssignResultView;
import com.lms.tutemanagement.service.TuteViews.DeleteResultView;
import com.lms.tutemanagement.service.TuteViews.EligibleStudentView;
import com.lms.tutemanagement.service.TuteViews.FulfillmentView;
import com.lms.tutemanagement.service.TuteViews.PaidSessionView;
import com.lms.tutemanagement.service.TuteViews.SyncResultView;
import com.lms.tutemanagement.service.TuteViews.TuteView;
import com.lms.tutemanagement.web.dto.AssignFulfillmentsRequest;
import com.lms.tutemanagement.web.dto.CancelFulfillmentRequest;
import com.lms.tutemanagement.web.dto.FulfillmentStatusRequest;
import com.lms.tutemanagement.web.dto.TuteRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TUTE-01 "Manage Tutes" of a course (docs/specs/tute-management-dispatch.md sections 3-5, 9).
 * Thin: every capability / course-scope / tenant / state decision is re-made by the services
 * ({@code @RequiresCapability} is the first gate only). Student data responses are {@code
 * no-store}.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/tutes")
public class TuteController {

	private final TuteService tuteService;

	private final TuteFulfillmentService fulfillmentService;

	public TuteController(TuteService tuteService, TuteFulfillmentService fulfillmentService) {
		this.tuteService = tuteService;
		this.fulfillmentService = fulfillmentService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_VIEW_TUTES)
	public ResponseEntity<ApiResponse<PageResponse<TuteView>>> list(@PathVariable UUID courseId,
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "false") boolean includeArchived, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ResponseEntity.ok(ApiResponse.success(tuteService.list(courseId, search, includeArchived, page, size)));
	}

	@GetMapping("/paid-sessions")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_VIEW_TUTES)
	public ResponseEntity<ApiResponse<List<PaidSessionView>>> paidSessions(@PathVariable UUID courseId,
			@RequestParam(required = false) String search) {
		return ResponseEntity.ok(ApiResponse.success(tuteService.paidSessions(courseId, search)));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_CREATE)
	public ResponseEntity<ApiResponse<TuteView>> create(@PathVariable UUID courseId,
			@RequestBody TuteRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(tuteService.create(courseId, input(request))));
	}

	@GetMapping("/{tuteId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_VIEW_TUTE)
	public ResponseEntity<ApiResponse<TuteView>> get(@PathVariable UUID courseId, @PathVariable UUID tuteId) {
		return ResponseEntity.ok(ApiResponse.success(tuteService.get(courseId, tuteId)));
	}

	@PutMapping("/{tuteId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_UPDATE)
	public ResponseEntity<ApiResponse<TuteView>> update(@PathVariable UUID courseId, @PathVariable UUID tuteId,
			@RequestBody TuteRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(tuteService.update(courseId, tuteId, input(request), request.version())));
	}

	@DeleteMapping("/{tuteId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_DELETE)
	public ResponseEntity<ApiResponse<DeleteResultView>> delete(@PathVariable UUID courseId,
			@PathVariable UUID tuteId) {
		return ResponseEntity.ok(ApiResponse.success(tuteService.delete(courseId, tuteId)));
	}

	@PostMapping("/{tuteId}/sync-payments")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_UPDATE)
	public ResponseEntity<ApiResponse<SyncResultView>> syncPayments(@PathVariable UUID courseId,
			@PathVariable UUID tuteId) {
		return ResponseEntity.ok(ApiResponse.success(fulfillmentService.syncPayments(courseId, tuteId)));
	}

	@GetMapping("/{tuteId}/fulfillments")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_VIEW_TUTE)
	public ResponseEntity<ApiResponse<PageResponse<FulfillmentView>>> fulfillments(@PathVariable UUID courseId,
			@PathVariable UUID tuteId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return noStore(fulfillmentService.list(courseId, tuteId, status, search, page, size));
	}

	@GetMapping("/{tuteId}/eligible-students")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_ENROLL)
	public ResponseEntity<ApiResponse<List<EligibleStudentView>>> eligibleStudents(@PathVariable UUID courseId,
			@PathVariable UUID tuteId, @RequestParam(required = false) String search) {
		return noStore(fulfillmentService.eligibleStudents(courseId, tuteId, search));
	}

	@PostMapping("/{tuteId}/fulfillments")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_ENROLL)
	public ResponseEntity<ApiResponse<AssignResultView>> assign(@PathVariable UUID courseId,
			@PathVariable UUID tuteId, @RequestBody AssignFulfillmentsRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(fulfillmentService.assign(courseId, tuteId, request.studentIds(), request.method())));
	}

	@PostMapping("/{tuteId}/fulfillments/{fulfillmentId}/cancel")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_UNROLL)
	public ResponseEntity<ApiResponse<FulfillmentView>> cancel(@PathVariable UUID courseId, @PathVariable UUID tuteId,
			@PathVariable UUID fulfillmentId, @RequestBody CancelFulfillmentRequest request) {
		return noStore(fulfillmentService.cancel(courseId, tuteId, fulfillmentId, request.reason()));
	}

	@PostMapping("/{tuteId}/fulfillments/{fulfillmentId}/status")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_UPDATE_STATUS)
	public ResponseEntity<ApiResponse<FulfillmentView>> status(@PathVariable UUID courseId, @PathVariable UUID tuteId,
			@PathVariable UUID fulfillmentId, @RequestBody FulfillmentStatusRequest request) {
		return noStore(
				fulfillmentService.updateStatus(courseId, tuteId, fulfillmentId, request.action(), request.method()));
	}

	private static TuteInput input(TuteRequest request) {
		return new TuteInput(request.name(), request.quantity(), request.paidLessonId(), request.paymentType(),
				request.price(), request.description());
	}

	private static <T> ResponseEntity<ApiResponse<T>> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
	}

}
