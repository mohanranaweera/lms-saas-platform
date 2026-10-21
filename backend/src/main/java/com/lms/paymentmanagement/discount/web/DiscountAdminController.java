package com.lms.paymentmanagement.discount.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.discount.service.DiscountAdminService;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.CourseOptionView;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountListView;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountRuleView;
import com.lms.paymentmanagement.discount.web.dto.DiscountActiveRequest;
import com.lms.paymentmanagement.discount.web.dto.DiscountRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DISC-01 tenant-admin discount rules (plan §5) - thin. {@code @PreAuthorize}
 * gates every endpoint on {@code DISCOUNTS} VIEW (reads) / CREATE_EDIT
 * (writes) as a first layer; {@link DiscountAdminService} re-checks the same
 * grants and enforces tenant scoping, validation, code masking and auditing.
 */
@RestController
@RequestMapping("/api/v1/admin/discounts")
public class DiscountAdminController {

	private final DiscountAdminService discountAdminService;

	public DiscountAdminController(DiscountAdminService discountAdminService) {
		this.discountAdminService = discountAdminService;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'VIEW')")
	public ResponseEntity<ApiResponse<DiscountListView>> list(@RequestParam(required = false) String status) {
		return ResponseEntity.ok(ApiResponse.success(discountAdminService.list(status)));
	}

	@GetMapping("/course-options")
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'VIEW')")
	public ResponseEntity<ApiResponse<List<CourseOptionView>>> courseOptions() {
		return ResponseEntity.ok(ApiResponse.success(discountAdminService.courseOptions()));
	}

	@GetMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'VIEW')")
	public ResponseEntity<ApiResponse<DiscountRuleView>> get(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(discountAdminService.get(id)));
	}

	@PostMapping
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DiscountRuleView>> create(@RequestBody DiscountRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(discountAdminService.create(request == null ? null : request.toInput())));
	}

	@PutMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DiscountRuleView>> update(@PathVariable UUID id,
			@RequestBody DiscountRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(discountAdminService.update(id, request == null ? null : request.toInput())));
	}

	@PatchMapping("/{id}/active")
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DiscountRuleView>> setActive(@PathVariable UUID id,
			@RequestBody DiscountActiveRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(discountAdminService.setActive(id, request == null ? null : request.active())));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('DISCOUNTS', 'CREATE_EDIT')")
	public ResponseEntity<Void> archive(@PathVariable UUID id) {
		discountAdminService.archive(id);
		return ResponseEntity.noContent().build();
	}

}
