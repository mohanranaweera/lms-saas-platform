package com.lms.tenantmanagement.web;

import com.lms.identityaccessservice.api.TenantAdminInvitationApi.FirstAdminView;
import com.lms.tenantmanagement.web.dto.TenantDetailResponse.FirstAdminResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.service.TenantApprovalService;
import com.lms.tenantmanagement.web.dto.TenantDetailResponse;
import com.lms.tenantmanagement.web.dto.TenantSummaryResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PADASH-1's tenant list/approval queue endpoints - the narrowed TEN-2 slice
 * (approve/reject of a {@code PENDING_APPROVAL} tenant only, plan §21 item 1
 * Option A; suspend/cancel of an already-active tenant is out of scope, plan
 * §6). Mirrors {@code LedgerController}/{@code AuditLogController}'s thin
 * -controller style exactly - every check beyond the class-level role gate
 * (Platform-Admin re-confirmation, status-precondition, transition
 * validation, audit write) lives in {@link TenantApprovalService}.
 *
 * <p>No endpoint here accepts a {@code tenantId}/{@code status} field from a
 * request body - the target tenant is always the path variable, and the
 * resulting status is determined solely by which endpoint was called.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminTenantController {

	private final TenantApprovalService tenantApprovalService;

	public PlatformAdminTenantController(TenantApprovalService tenantApprovalService) {
		this.tenantApprovalService = tenantApprovalService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<TenantSummaryResponse>>> list(
			@RequestParam(required = false) TenantStatus status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Page<Tenant> page = tenantApprovalService.list(status, pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page.map(PlatformAdminTenantController::toSummary))));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<TenantDetailResponse>> getDetail(@PathVariable UUID id) {
		Tenant tenant = tenantApprovalService.getDetail(id);
		return ResponseEntity.ok(ApiResponse.success(toDetail(tenant)));
	}

	@PostMapping("/{id}/approve")
	public ResponseEntity<ApiResponse<TenantDetailResponse>> approve(@PathVariable UUID id) {
		Tenant tenant = tenantApprovalService.approve(id);
		return ResponseEntity.ok(ApiResponse.success(toDetail(tenant)));
	}

	@PostMapping("/{id}/reject")
	public ResponseEntity<ApiResponse<TenantDetailResponse>> reject(@PathVariable UUID id) {
		Tenant tenant = tenantApprovalService.reject(id);
		return ResponseEntity.ok(ApiResponse.success(toDetail(tenant)));
	}

	private static TenantSummaryResponse toSummary(Tenant tenant) {
		return new TenantSummaryResponse(tenant.getId(), tenant.getName(), tenant.getSubdomain(), tenant.getStatus(),
				tenant.getRequestedPlan(), tenant.getCreatedAt());
	}

	/**
	 * AUTH-PWD-01: invite the tenant's first Tenant Admin, resend its
	 * invitation, or correct its email. The tenant is the path variable only.
	 */
	@PostMapping("/{id}/admin-invitation")
	public ResponseEntity<ApiResponse<FirstAdminResponse>> inviteAdmin(@PathVariable UUID id,
			@Valid @RequestBody(required = false) AdminInvitationRequest request) {
		FirstAdminView view = tenantApprovalService.inviteAdmin(id, (request == null) ? null : request.email());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(toFirstAdmin(view)));
	}

	/** Optional corrected/new email for the invitation. */
	public record AdminInvitationRequest(@Email @Size(max = 255) String email) {

	}

	private TenantDetailResponse toDetail(Tenant tenant) {
		return new TenantDetailResponse(tenant.getId(), tenant.getName(), tenant.getSubdomain(), tenant.getStatus(),
				tenant.getRequestedPlan(), tenant.getContactName(), tenant.getContactEmail(),
				tenant.getContactPhone(), tenant.getCreatedAt(),
				toFirstAdmin(tenantApprovalService.firstAdmin(tenant.getId())));
	}

	private static FirstAdminResponse toFirstAdmin(FirstAdminView view) {
		return new FirstAdminResponse(view.status(), view.emailMasked(), view.invitedAt(), view.inviteExpiresAt());
	}

}
