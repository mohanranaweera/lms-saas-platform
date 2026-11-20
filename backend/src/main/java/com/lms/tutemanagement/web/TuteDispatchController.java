package com.lms.tutemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchFilter;
import com.lms.tutemanagement.service.TuteDispatchService;
import com.lms.tutemanagement.service.TuteViews.ConfirmDispatchView;
import com.lms.tutemanagement.service.TuteViews.DispatchQueueView;
import com.lms.tutemanagement.service.TuteViews.FilterOptionsView;
import com.lms.tutemanagement.web.dto.ConfirmDispatchRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TUTE-01 Tute Dispatch queue and dispatch confirmation (spec section 7). The label PDF is
 * served by {@code reportinganalytics.web.TuteDispatchLabelPdfController}. Thin: the service
 * re-checks the capability and the caller's course scope on every call.
 */
@RestController
@RequestMapping("/api/v1/tute-dispatch")
public class TuteDispatchController {

	private final TuteDispatchService dispatchService;

	public TuteDispatchController(TuteDispatchService dispatchService) {
		this.dispatchService = dispatchService;
	}

	@GetMapping("/queue")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_DISPATCH_VIEW)
	public ResponseEntity<ApiResponse<DispatchQueueView>> queue(@RequestParam(required = false) String search,
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) UUID tuteId,
			@RequestParam(defaultValue = "false") boolean twoPlus, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String direction, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return noStore(dispatchService.queue(new DispatchFilter(search, courseId, tuteId, twoPlus), sort, direction,
				page, size));
	}

	@GetMapping("/filter-options")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_DISPATCH_VIEW)
	public ResponseEntity<ApiResponse<FilterOptionsView>> filterOptions() {
		return noStore(dispatchService.filterOptions());
	}

	@PostMapping("/confirm")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_UPDATE_STATUS)
	public ResponseEntity<ApiResponse<ConfirmDispatchView>> confirm(@RequestBody ConfirmDispatchRequest request) {
		return noStore(dispatchService.confirm(request.fulfillmentIds()));
	}

	private static <T> ResponseEntity<ApiResponse<T>> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
	}

}
