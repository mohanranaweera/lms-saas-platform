package com.lms.exammanagement.web;

import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.api.Capability;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.service.questionbank.ModuleQuestionBankService;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.CopyResultView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ExamMediaView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ModuleQuestionCountView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.QuestionView;
import com.lms.exammanagement.service.questionbank.QuestionContentValidator.OptionInput;
import com.lms.exammanagement.service.questionbank.QuestionContentValidator.QuestionInput;
import com.lms.exammanagement.web.dto.QuestionCopyRequest;
import com.lms.exammanagement.web.dto.QuestionUpsertRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Module question bank (Klass Exam Module Slice A, {@code docs/api/exam-management-v2.md}).
 * Thin: every authorization / tenant / module-ownership decision is made by {@link
 * ModuleQuestionBankService}; {@code isAuthenticated()} is only the coarse gate.
 */
@RestController
@RequestMapping("/api/v1")
public class ModuleQuestionBankController {

	private final ModuleQuestionBankService service;

	public ModuleQuestionBankController(ModuleQuestionBankService service) {
		this.service = service;
	}

	@GetMapping("/courses/{courseId}/modules/{moduleId}/questions")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_VIEW)
	public ResponseEntity<ApiResponse<PageResponse<QuestionView>>> list(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return ResponseEntity.ok(ApiResponse.success(service.list(courseId, moduleId, page, size)));
	}

	@PostMapping("/courses/{courseId}/modules/{moduleId}/questions")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_ADD)
	public ResponseEntity<ApiResponse<QuestionView>> create(@PathVariable UUID courseId, @PathVariable UUID moduleId,
			@Valid @RequestBody QuestionUpsertRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(service.create(courseId, moduleId, toInput(request))));
	}

	@PostMapping("/courses/{courseId}/modules/{moduleId}/questions/copy")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_ADD)
	public ResponseEntity<ApiResponse<CopyResultView>> copy(@PathVariable UUID courseId, @PathVariable UUID moduleId,
			@Valid @RequestBody QuestionCopyRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
				service.copy(courseId, moduleId, request.targetCourseId(), request.targetModuleId())));
	}

	@GetMapping("/courses/{courseId}/modules/question-counts")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_VIEW)
	public ResponseEntity<ApiResponse<List<ModuleQuestionCountView>>> questionCounts(@PathVariable UUID courseId) {
		return ResponseEntity.ok(ApiResponse.success(service.questionCounts(courseId)));
	}

	@GetMapping("/exam-questions/{questionId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_VIEW)
	public ResponseEntity<ApiResponse<QuestionView>> get(@PathVariable UUID questionId) {
		return ResponseEntity.ok(ApiResponse.success(service.get(questionId)));
	}

	@PutMapping("/exam-questions/{questionId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_EDIT)
	public ResponseEntity<ApiResponse<QuestionView>> update(@PathVariable UUID questionId,
			@Valid @RequestBody QuestionUpsertRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.update(questionId, toInput(request))));
	}

	@DeleteMapping("/exam-questions/{questionId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> delete(@PathVariable UUID questionId) {
		service.delete(questionId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/exam-media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.MODULES_QUESTIONS_ADD)
	public ResponseEntity<ApiResponse<ExamMediaView>> uploadImage(@RequestPart("file") MultipartFile file) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.uploadImage(file)));
	}

	private static QuestionInput toInput(QuestionUpsertRequest request) {
		List<OptionInput> options = request.options() == null ? List.of()
				: request.options()
					.stream()
					.map(option -> option == null ? null
							: new OptionInput(option.id(), option.html(), Boolean.TRUE.equals(option.correct())))
					.toList();
		return new QuestionInput(request.bodyHtml(), request.marks(), request.explanationHtml(), options);
	}

}
