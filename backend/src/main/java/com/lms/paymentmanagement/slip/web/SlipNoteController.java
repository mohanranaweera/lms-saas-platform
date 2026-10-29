package com.lms.paymentmanagement.slip.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.slip.service.SlipNoteService;
import com.lms.paymentmanagement.slip.web.dto.SlipNoteRequest;
import com.lms.paymentmanagement.slip.web.dto.SlipNoteResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-HUB-01: staff notes on a payment slip. Coarse {@code isAuthenticated()}
 * gate only - {@link SlipNoteService} enforces the real {@code PAYMENTS_SLIPS}
 * VIEW / CREATE_EDIT checks. There is deliberately no update or delete route.
 */
@RestController
@RequestMapping("/api/v1/payment-slips")
public class SlipNoteController {

	private final SlipNoteService slipNoteService;

	public SlipNoteController(SlipNoteService slipNoteService) {
		this.slipNoteService = slipNoteService;
	}

	@GetMapping("/{slipId}/notes")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<SlipNoteResponse>>> listNotes(@PathVariable UUID slipId) {
		List<SlipNoteResponse> notes = slipNoteService.listNotes(slipId).stream().map(SlipNoteResponse::from).toList();
		return ResponseEntity.ok(ApiResponse.success(notes));
	}

	@PostMapping("/{slipId}/notes")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SlipNoteResponse>> addNote(@PathVariable UUID slipId,
			@Valid @RequestBody SlipNoteRequest request) {
		SlipNoteResponse note = SlipNoteResponse.from(slipNoteService.addNote(slipId, request.body()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(note));
	}

}
