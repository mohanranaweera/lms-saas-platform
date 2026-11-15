package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleStatus;
import com.lms.financeexpensemanagement.service.FinanceScheduleService;
import com.lms.financeexpensemanagement.service.FinanceScheduleService.OccurrenceView;
import com.lms.financeexpensemanagement.service.FinanceScheduleService.ScheduleCommand;
import com.lms.financeexpensemanagement.service.FinanceScheduleService.ScheduleView;
import com.lms.financeexpensemanagement.web.dto.FinanceRequests;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
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

/** FIN-01 Scheduled tab. Posting an occurrence is a manual, authorised action. */
@RestController
@RequestMapping("/api/v1/finance/schedules")
public class FinanceScheduleController {

	private final FinanceScheduleService scheduleService;

	public FinanceScheduleController(FinanceScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_VIEW_SCHEDULES)
	public ResponseEntity<ApiResponse<PageResponse<ScheduleView>>> search(
			@RequestParam(required = false) String search, @RequestParam(required = false) ScheduleStatus status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(scheduleService.search(search, status, page,
				size))));
	}

	@GetMapping("/due")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_VIEW_SCHEDULES)
	public ResponseEntity<ApiResponse<List<OccurrenceView>>> due() {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.dueOccurrences()));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_MANAGE_SCHEDULES)
	public ResponseEntity<ApiResponse<ScheduleView>> create(@RequestBody FinanceRequests.Schedule request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(scheduleService.create(toCommand(request))));
	}

	@GetMapping("/{scheduleId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_VIEW_SCHEDULES)
	public ResponseEntity<ApiResponse<ScheduleView>> get(@PathVariable UUID scheduleId) {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.get(scheduleId)));
	}

	@PutMapping("/{scheduleId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_MANAGE_SCHEDULES)
	public ResponseEntity<ApiResponse<ScheduleView>> update(@PathVariable UUID scheduleId,
			@RequestBody FinanceRequests.Schedule request) {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.update(scheduleId, toCommand(request))));
	}

	@PostMapping("/{scheduleId}/cancel")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_MANAGE_SCHEDULES)
	public ResponseEntity<ApiResponse<ScheduleView>> cancel(@PathVariable UUID scheduleId) {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.cancel(scheduleId)));
	}

	@GetMapping("/{scheduleId}/occurrences")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_VIEW_SCHEDULES)
	public ResponseEntity<ApiResponse<List<OccurrenceView>>> occurrences(@PathVariable UUID scheduleId) {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.occurrences(scheduleId)));
	}

	@PostMapping("/occurrences/{occurrenceId}/post")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_RECORD_TRANSACTION)
	public ResponseEntity<ApiResponse<OccurrenceView>> post(@PathVariable UUID occurrenceId) {
		OccurrenceView result = scheduleService.postOccurrence(occurrenceId);
		// A validation failure is committed on the occurrence (FAILED + lastError) and reported as 409.
		HttpStatus status = result.status() == com.lms.financeexpensemanagement.domain.FinanceEnums.OccurrenceStatus.FAILED
				? HttpStatus.CONFLICT : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(ApiResponse.success(result));
	}

	@PostMapping("/occurrences/{occurrenceId}/skip")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_MANAGE_SCHEDULES)
	public ResponseEntity<ApiResponse<OccurrenceView>> skip(@PathVariable UUID occurrenceId) {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.skipOccurrence(occurrenceId)));
	}

	private static ScheduleCommand toCommand(FinanceRequests.Schedule r) {
		return new ScheduleCommand(r.name(), r.type(), r.amount(), r.currency(), r.accountId(), r.fromAccountId(),
				r.toAccountId(), r.categoryId(), r.note(), r.scheduleType(), r.scheduledDate(), r.dayOfMonth());
	}

}
