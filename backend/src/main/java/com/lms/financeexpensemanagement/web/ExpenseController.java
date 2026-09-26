package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import com.lms.financeexpensemanagement.service.ExpenseService;
import com.lms.financeexpensemanagement.service.ExpenseService.ExpenseView;
import com.lms.financeexpensemanagement.service.ExpenseService.NewExpenseCommand;
import com.lms.financeexpensemanagement.web.dto.AttachmentUrlResponse;
import com.lms.financeexpensemanagement.web.dto.ExpenseCreateRequest;
import com.lms.financeexpensemanagement.web.dto.ExpenseVoidRequest;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Wave 7 (PAR-23-01/05) - expenses. Thin: the coarse {@code
 * isAuthenticated()} gate here is backed by the real {@code
 * FINANCE_EXPENSES} permission check inside {@link ExpenseService} on every
 * call. There is deliberately no PUT/PATCH/DELETE - expenses are append-only;
 * {@code POST /{id}/void} is the only mutation.
 */
@RestController
@RequestMapping("/api/v1/finance/expenses")
public class ExpenseController {

	private final ExpenseService expenseService;

	public ExpenseController(ExpenseService expenseService) {
		this.expenseService = expenseService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<ExpenseView>>> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID categoryId, @RequestParam(required = false) ExpenseMethod method,
			@RequestParam(defaultValue = "false") boolean includeVoided,
			@PageableDefault(size = 20, sort = { "expenseDate", "createdAt" },
					direction = Sort.Direction.DESC) Pageable pageable) {
		return ResponseEntity.ok(ApiResponse.success(PageResponse
			.from(expenseService.search(from, to, categoryId, method, includeVoided, pageable))));
	}

	@GetMapping("/{expenseId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExpenseView>> get(@PathVariable UUID expenseId) {
		return ResponseEntity.ok(ApiResponse.success(expenseService.get(expenseId)));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExpenseView>> create(@Valid @ModelAttribute ExpenseCreateRequest request,
			@RequestPart(value = "attachment", required = false) MultipartFile attachment) {
		ExpenseView view = expenseService.create(new NewExpenseCommand(request.categoryId(), request.expenseDate(),
				request.description(), request.amount(), request.method(), request.reference()), attachment);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(view));
	}

	@PostMapping("/{expenseId}/void")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExpenseView>> voidExpense(@PathVariable UUID expenseId,
			@Valid @RequestBody ExpenseVoidRequest request) {
		return ResponseEntity.ok(ApiResponse.success(expenseService.voidExpense(expenseId, request.reason())));
	}

	@GetMapping("/{expenseId}/attachment-url")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttachmentUrlResponse>> attachmentUrl(@PathVariable UUID expenseId) {
		SignedDownloadUrl signed = expenseService.attachmentUrl(expenseId);
		return ResponseEntity.ok(ApiResponse.success(new AttachmentUrlResponse(signed.url(), signed.expiresAt())));
	}

}
