package com.lms.ledgersettlementmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementStatus;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.RateView;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.SettlementDetail;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.SettlementView;
import com.lms.ledgersettlementmanagement.web.dto.TeacherSettlementAdjustmentRequest;
import com.lms.ledgersettlementmanagement.web.dto.TeacherSettlementCalculateRequest;
import com.lms.ledgersettlementmanagement.web.dto.TeacherSettlementMarkPaidRequest;
import com.lms.ledgersettlementmanagement.web.dto.TeacherShareRateRequest;
import com.lms.usermanagement.api.TeacherLookupApi;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 7 teacher settlement foundation (PAR-24-02/03/04). Thin: real
 * authorization ({@code FINANCE_EXPENSES}) is enforced in {@link
 * TeacherSettlementService} on every call. No PUT/DELETE: rates and
 * settlements are append-only; corrections are adjustments.
 */
@RestController
@RequestMapping("/api/v1/finance")
public class TeacherSettlementController {

	private final TeacherSettlementService settlementService;

	public TeacherSettlementController(TeacherSettlementService settlementService) {
		this.settlementService = settlementService;
	}

	@GetMapping("/teachers")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<TeacherLookupApi.TeacherSummary>>> listPayees() {
		return ResponseEntity.ok(ApiResponse.success(settlementService.listPayees()));
	}

	@GetMapping("/teacher-share-rates")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<RateView>>> listRates(@RequestParam(required = false) UUID teacherId) {
		return ResponseEntity.ok(ApiResponse.success(settlementService.listRates(teacherId)));
	}

	@PostMapping("/teacher-share-rates")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RateView>> addRate(@Valid @RequestBody TeacherShareRateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(settlementService.addRate(request.teacherId(), request.sharePercent(),
					request.effectiveFrom())));
	}

	@GetMapping("/teacher-settlements")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<SettlementView>>> list(
			@RequestParam(required = false) UUID teacherId,
			@RequestParam(required = false) TeacherSettlementStatus status,
			@RequestParam(required = false) TeacherSettlementKind kind,
			@PageableDefault(size = 20, sort = "calculatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return ResponseEntity
			.ok(ApiResponse.success(PageResponse.from(settlementService.list(teacherId, status, kind, pageable))));
	}

	@PostMapping("/teacher-settlements")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SettlementDetail>> calculate(
			@Valid @RequestBody TeacherSettlementCalculateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(settlementService.calculate(request.teacherId(), request.periodStart(),
					request.periodEnd())));
	}

	@GetMapping("/teacher-settlements/{settlementId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SettlementDetail>> get(@PathVariable UUID settlementId) {
		return ResponseEntity.ok(ApiResponse.success(settlementService.get(settlementId)));
	}

	@PostMapping("/teacher-settlements/{settlementId}/mark-paid")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SettlementDetail>> markPaid(@PathVariable UUID settlementId,
			@Valid @RequestBody(required = false) TeacherSettlementMarkPaidRequest request) {
		String reference = (request == null) ? null : request.payoutReference();
		return ResponseEntity.ok(ApiResponse.success(settlementService.markPaid(settlementId, reference)));
	}

	@PostMapping("/teacher-settlements/{settlementId}/adjustments")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SettlementDetail>> adjust(@PathVariable UUID settlementId,
			@Valid @RequestBody TeacherSettlementAdjustmentRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(settlementService.adjust(settlementId, request.amount(), request.reason())));
	}

}
