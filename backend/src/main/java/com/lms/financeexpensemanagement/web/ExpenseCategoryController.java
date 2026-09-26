package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.financeexpensemanagement.service.ExpenseCategoryService;
import com.lms.financeexpensemanagement.service.ExpenseCategoryService.CategoryView;
import com.lms.financeexpensemanagement.web.dto.ExpenseCategoryRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 7 (PAR-23-01) - expense categories. No DELETE endpoint: categories
 * are archived, never deleted. Real authorization is enforced in {@link
 * ExpenseCategoryService} ({@code FINANCE_EXPENSES}).
 */
@RestController
@RequestMapping("/api/v1/finance/expense-categories")
public class ExpenseCategoryController {

	private final ExpenseCategoryService categoryService;

	public ExpenseCategoryController(ExpenseCategoryService categoryService) {
		this.categoryService = categoryService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<CategoryView>>> list(
			@RequestParam(defaultValue = "false") boolean includeArchived) {
		return ResponseEntity.ok(ApiResponse.success(categoryService.list(includeArchived)));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CategoryView>> create(@Valid @RequestBody ExpenseCategoryRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(categoryService.create(request.name(), request.description())));
	}

	@PutMapping("/{categoryId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CategoryView>> update(@PathVariable UUID categoryId,
			@Valid @RequestBody ExpenseCategoryRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(categoryService.update(categoryId, request.name(), request.description())));
	}

	@PostMapping("/{categoryId}/archive")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CategoryView>> archive(@PathVariable UUID categoryId) {
		return ResponseEntity.ok(ApiResponse.success(categoryService.setArchived(categoryId, true)));
	}

	@PostMapping("/{categoryId}/unarchive")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CategoryView>> unarchive(@PathVariable UUID categoryId) {
		return ResponseEntity.ok(ApiResponse.success(categoryService.setArchived(categoryId, false)));
	}

}
