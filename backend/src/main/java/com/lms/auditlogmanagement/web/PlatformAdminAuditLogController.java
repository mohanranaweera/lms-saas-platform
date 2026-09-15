package com.lms.auditlogmanagement.web;

import com.lms.auditlogmanagement.service.PlatformAdminAuditLogQueryService;
import com.lms.auditlogmanagement.web.dto.PlatformAuditLogEntryResponse;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PADASH-2's platform-level audit log + per-tenant drill-down endpoints
 * (plan §9.3/§10). Structurally distinct from {@code AuditLogController}
 * (AUDIT-3, tenant-scoped, {@code ADR-014}-allowlisted) - a different class,
 * a different service, a different repository method. Only {@code GET}
 * mappings exist on this controller - no {@code PUT}/{@code PATCH}/{@code
 * DELETE} route exists anywhere on the platform audit log, per plan §13.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/audit-log")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminAuditLogController {

	private final PlatformAdminAuditLogQueryService platformAdminAuditLogQueryService;

	public PlatformAdminAuditLogController(PlatformAdminAuditLogQueryService platformAdminAuditLogQueryService) {
		this.platformAdminAuditLogQueryService = platformAdminAuditLogQueryService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<PlatformAuditLogEntryResponse>>> getPlatformLog(
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(required = false) String action, @PageableDefault(size = 20) Pageable pageable) {
		Page<PlatformAuditLogEntryResponse> page = platformAdminAuditLogQueryService.getPlatformLog(from, to, action,
				pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@GetMapping("/tenants/{tenantId}")
	public ResponseEntity<ApiResponse<PageResponse<PlatformAuditLogEntryResponse>>> getTenantDrillDown(
			@PathVariable UUID tenantId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) String action,
			@PageableDefault(size = 20) Pageable pageable) {
		Page<PlatformAuditLogEntryResponse> page = platformAdminAuditLogQueryService.getTenantDrillDown(tenantId, from,
				to, action, pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

}
