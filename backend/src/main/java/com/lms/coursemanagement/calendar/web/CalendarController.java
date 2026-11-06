package com.lms.coursemanagement.calendar.web;

import com.lms.common.api.ApiResponse;
import com.lms.coursemanagement.api.CalendarOccurrences;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceService;
import com.lms.coursemanagement.calendar.web.dto.CalendarCourseOptionResponse;
import com.lms.coursemanagement.calendar.web.dto.CalendarOccurrencesResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAL-01 "Calendar &amp; Timetable" reads (docs/specs/class-calendar.md
 * §6.3-§6.4) for any authenticated tenant user; the course scope is derived
 * server-side from the caller's role in {@link ClassOccurrenceService} -
 * nothing in the request widens it. The timetable PDF (§6.5) is served by
 * reporting-analytics through {@code ClassCalendarApi}.
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

	private final ClassOccurrenceService occurrenceService;

	public CalendarController(ClassOccurrenceService occurrenceService) {
		this.occurrenceService = occurrenceService;
	}

	@GetMapping("/occurrences")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CalendarOccurrencesResponse>> occurrences(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID courseId) {
		CalendarOccurrences result = occurrenceService.listCallerOccurrences(from, to, courseId);
		CalendarOccurrencesResponse response = new CalendarOccurrencesResponse(result.timezone(), result.serverNow(),
				result.from(), result.to(),
				result.occurrences()
					.stream()
					.map(o -> new CalendarOccurrencesResponse.Occurrence(o.id(), o.source(), o.courseId(),
							o.courseName(), o.courseSlug(), o.start(), o.end(), o.localDate(),
							ClassScheduleController.hhmm(o.startLocalTime()),
							ClassScheduleController.hhmm(o.endLocalTime()), o.teacherNames(), o.status(),
							o.cancelled(), o.sessionId(), o.sessionTitle(), o.replacesOccurrenceId()))
					.toList());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(response));
	}

	@GetMapping("/courses")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<CalendarCourseOptionResponse>>> courses() {
		return ResponseEntity.ok(ApiResponse.success(occurrenceService.listCallerCourses()
			.stream()
			.map(option -> new CalendarCourseOptionResponse(option.id(), option.name()))
			.toList()));
	}

}
