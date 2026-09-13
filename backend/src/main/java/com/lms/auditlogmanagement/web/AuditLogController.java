package com.lms.auditlogmanagement.web;

import com.lms.auditlogmanagement.service.AuditLogQueryService;
import com.lms.auditlogmanagement.service.AuditLogSearchCriteria;
import com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AUDIT-3's read-only audit log search endpoint. Mirrors {@code
 * com.lms.ledgersettlementmanagement.web.LedgerController}'s exact style: a
 * thin, coarsely-gated controller - the real authorization (both the
 * existing {@code AUDIT_LOG}/{@code VIEW} grant check and the narrower
 * Institute-Owner/Read-only-Auditor allowlist) lives in {@link
 * AuditLogQueryService#search}, never here. {@code tenantId} is never an
 * accepted parameter in any form on this endpoint - tenant identity is
 * resolved exclusively from the trusted authenticated context downstream, by
 * {@code AuditLogRepository}'s inherited tenant-scoped {@code
 * findAll(Specification, Pageable)}.
 */
@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {

	private final AuditLogQueryService auditLogQueryService;

	public AuditLogController(AuditLogQueryService auditLogQueryService) {
		this.auditLogQueryService = auditLogQueryService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<AuditLogEntryResponse>>> search(
			@PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(required = false) String action, @RequestParam(required = false) String targetEntity) {
		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(from, to, action, targetEntity);
		Page<AuditLogEntryResponse> page = auditLogQueryService.search(criteria, pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

}
