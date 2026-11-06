package com.lms.coursemanagement.calendar.web;

import com.lms.common.api.ApiResponse;
import com.lms.coursemanagement.calendar.service.ClassScheduleService;
import com.lms.coursemanagement.calendar.service.ClassScheduleValidator.SlotInput;
import com.lms.coursemanagement.calendar.service.ClassScheduleView;
import com.lms.coursemanagement.calendar.web.dto.ClassScheduleResponse;
import com.lms.coursemanagement.calendar.web.dto.ClassScheduleSaveRequest;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAL-01 "Edit Class Schedule" (docs/specs/class-calendar.md §6.1-§6.2).
 * Thin: tenant, caller and authorization (staff matrix or owning teacher via
 * {@code CourseAccessGuard}) are all resolved in {@link ClassScheduleService}.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/class-schedule")
public class ClassScheduleController {

	private final ClassScheduleService classScheduleService;

	public ClassScheduleController(ClassScheduleService classScheduleService) {
		this.classScheduleService = classScheduleService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassScheduleResponse>> getSchedule(@PathVariable UUID courseId) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(classScheduleService.getSchedule(courseId))));
	}

	@PutMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassScheduleResponse>> saveSchedule(@PathVariable UUID courseId,
			@RequestBody ClassScheduleSaveRequest request) {
		List<SlotInput> slots = request.slots() == null ? null
				: request.slots()
					.stream()
					.map(slot -> slot == null ? null
							: new SlotInput(slot.dayOfWeek(), slot.startTime(), slot.endTime()))
					.toList();
		ClassScheduleView view = classScheduleService.saveSchedule(courseId, new ClassScheduleService.SaveCommand(
				request.expectedRevisionId(), request.effectiveFrom(), request.effectiveTo(), slots));
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private static ClassScheduleResponse toResponse(ClassScheduleView view) {
		return new ClassScheduleResponse(view.courseId(), view.courseName(), view.timezone(), view.revisionId(),
				view.effectiveFrom(), view.effectiveTo(), view.updatedAt(), view.updatedByName(), view.canEdit(),
				view.slots()
					.stream()
					.map(slot -> new ClassScheduleResponse.Slot(slot.id(), slot.dayOfWeek().name(),
							hhmm(slot.startTime()), hhmm(slot.endTime())))
					.toList());
	}

	/** {@code HH:mm} (the API's minute-precision local time format). */
	static String hhmm(LocalTime time) {
		return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
	}

}
