package com.lms.ledgersettlementmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.ledgersettlementmanagement.service.PlatformAdminLedgerQueryService;
import com.lms.ledgersettlementmanagement.web.dto.PlatformLedgerEntryResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PADASH-2's cross-tenant, read-only payment/ledger dashboard endpoints
 * (plan §9.2/§10). No refund/adjustment/other state-changing action is
 * reachable from here - any such action stays on the existing, tenant-scoped
 * payment/refund endpoints, per plan §4.2 step 4/§17.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/payments")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminLedgerController {

	private final PlatformAdminLedgerQueryService platformAdminLedgerQueryService;

	public PlatformAdminLedgerController(PlatformAdminLedgerQueryService platformAdminLedgerQueryService) {
		this.platformAdminLedgerQueryService = platformAdminLedgerQueryService;
	}

	@GetMapping("/dashboard")
	public ResponseEntity<ApiResponse<PageResponse<PlatformLedgerEntryResponse>>> getDashboard(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Page<PlatformLedgerEntryResponse> page = platformAdminLedgerQueryService.getPlatformDashboard(pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@GetMapping("/tenants/{tenantId}")
	public ResponseEntity<ApiResponse<PageResponse<PlatformLedgerEntryResponse>>> getTenantDrillDown(
			@PathVariable UUID tenantId,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Page<PlatformLedgerEntryResponse> page = platformAdminLedgerQueryService.getTenantDrillDown(tenantId,
				pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

}
