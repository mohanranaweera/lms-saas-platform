package com.lms.ledgersettlementmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.CreateResult;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.EntryView;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.PostResult;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.PostingRequest;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.WalletView;
import com.lms.ledgersettlementmanagement.web.dto.TeacherWalletRequests;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEACHER-WALLET-01 / ADR-036: Teachers -> Show Wallet. One teacher per call
 * (the teacher table never loads balances). Tenant comes from the
 * authenticated context; a teacher id from another tenant is 404. Posting
 * responses are 201 for a new entry and 200 for an idempotent replay.
 */
@RestController
@RequestMapping("/api/v1/finance/teachers/{teacherId}/wallet")
public class TeacherWalletController {

	private final TeacherWalletService walletService;

	public TeacherWalletController(TeacherWalletService walletService) {
		this.walletService = walletService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHERS_VIEW_WALLET)
	public ResponseEntity<ApiResponse<WalletView>> get(@PathVariable UUID teacherId) {
		return ResponseEntity.ok(ApiResponse.success(walletService.get(teacherId)));
	}

	@GetMapping("/entries")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHERS_VIEW_WALLET)
	public ResponseEntity<ApiResponse<List<EntryView>>> entries(@PathVariable UUID teacherId,
			@RequestParam(required = false) TeacherSettlementKind kind,
			@RequestParam(defaultValue = "10") int limit) {
		return ResponseEntity.ok(ApiResponse.success(walletService.entries(teacherId, kind, limit)));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHERS_WALLET_CREATE)
	public ResponseEntity<ApiResponse<WalletView>> create(@PathVariable UUID teacherId,
			@RequestBody TeacherWalletRequests.Create request) {
		CreateResult result = walletService.create(teacherId, request.openingBalance(), request.currency());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(ApiResponse.success(result.wallet()));
	}

	@PostMapping("/pay-ins")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHERS_WALLET_PAY_IN)
	public ResponseEntity<ApiResponse<PostResult>> payIn(@PathVariable UUID teacherId,
			@RequestBody TeacherWalletRequests.Posting request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		return respond(walletService.payIn(teacherId, toPosting(request), idempotencyKey));
	}

	@PostMapping("/pay-outs")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHERS_WALLET_PAY_OUT)
	public ResponseEntity<ApiResponse<PostResult>> payOut(@PathVariable UUID teacherId,
			@RequestBody TeacherWalletRequests.Posting request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		return respond(walletService.payOut(teacherId, toPosting(request), idempotencyKey));
	}

	@PostMapping("/adjustments")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHERS_WALLET_ADJUST)
	public ResponseEntity<ApiResponse<PostResult>> adjust(@PathVariable UUID teacherId,
			@RequestBody TeacherWalletRequests.Adjustment request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		return respond(walletService.adjust(teacherId, request.targetBalance(), request.expectedBalance(),
				request.reason(), idempotencyKey));
	}

	private static PostingRequest toPosting(TeacherWalletRequests.Posting request) {
		return new PostingRequest(request.amount(), request.description(), request.currency(), request.accountId(),
				request.categoryId(), request.expectedBalance());
	}

	private static ResponseEntity<ApiResponse<PostResult>> respond(PostResult result) {
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(ApiResponse.success(result));
	}

}
