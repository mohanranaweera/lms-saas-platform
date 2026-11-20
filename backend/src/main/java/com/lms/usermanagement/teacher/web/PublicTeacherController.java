package com.lms.usermanagement.teacher.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.usermanagement.teacher.service.PublicTeacherService;
import com.lms.usermanagement.teacher.web.dto.PublicTeacherResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/teachers")
public class PublicTeacherController {

	private final PublicTeacherService publicTeacherService;

	public PublicTeacherController(PublicTeacherService publicTeacherService) {
		this.publicTeacherService = publicTeacherService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<PublicTeacherResponse>>> listTeachers(
			@PageableDefault(size = 12, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
		return ResponseEntity.ok(ApiResponse.success(publicTeacherService.listTeachers(pageable)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<PublicTeacherResponse>> getTeacher(@PathVariable java.util.UUID id) {
		return ResponseEntity.ok(ApiResponse.success(publicTeacherService.getTeacher(id)));
	}
}
