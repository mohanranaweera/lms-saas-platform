package com.lms.tutemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tutemanagement.service.StudentTuteService;
import com.lms.tutemanagement.service.TuteViews.StudentTuteView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * TUTE-02: the student's own tutes of a course ("Tute Delivery" tab). Student only; scoping
 * (caller, tenant, course) lives in {@link StudentTuteService}. {@code no-store}.
 */
@RestController
public class StudentTuteController {

	private final StudentTuteService studentTuteService;

	public StudentTuteController(StudentTuteService studentTuteService) {
		this.studentTuteService = studentTuteService;
	}

	@GetMapping("/api/v1/courses/{courseId}/my-tutes")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<List<StudentTuteView>>> myTutes(@PathVariable UUID courseId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(studentTuteService.myTutes(courseId)));
	}

}
