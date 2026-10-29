package com.lms.paymentmanagement.order.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.order.service.AdminOrderDetailView;
import com.lms.paymentmanagement.order.service.AdminOrderQueryService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-HUB-01: the staff Payments hub's whole-order view. Kept apart from the
 * student-facing {@link OrderController} (same {@code /api/v1/orders} prefix):
 * this read is staff {@code PAYMENTS_SLIPS}/{@code VIEW} only, enforced in
 * {@link AdminOrderQueryService}; the coarse gate here is authentication only.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class AdminOrderController {

	private final AdminOrderQueryService adminOrderQueryService;

	public AdminOrderController(AdminOrderQueryService adminOrderQueryService) {
		this.adminOrderQueryService = adminOrderQueryService;
	}

	@GetMapping("/{id}/admin-detail")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AdminOrderDetailView>> getAdminOrderDetail(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(adminOrderQueryService.getOrderDetail(id)));
	}

}
