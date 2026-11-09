package com.lms.tutemanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A tute request that conflicts with stock, state or the current selection (TUTE-01). The code is
 * the machine-readable {@code error.code}; messages are client-safe and {@code details} carries
 * only values the caller already knows (its own ids, counts).
 */
public class TuteException extends ApplicationException {

	private final Map<String, Object> details;

	public TuteException(HttpStatus status, String code, String message, Map<String, Object> details) {
		super(status, code, message);
		this.details = details == null || details.isEmpty() ? null : Map.copyOf(details);
	}

	@Override
	public Map<String, Object> getDetails() {
		return details;
	}

	static TuteException outOfStock(long available, long requested) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("available", available);
		details.put("requested", requested);
		return new TuteException(HttpStatus.CONFLICT, ApiErrorCodes.TUTE_OUT_OF_STOCK,
				"Only " + available + " copies of this tute are available", details);
	}

	static TuteException quantityBelowAllocated(long allocated) {
		return new TuteException(HttpStatus.CONFLICT, ApiErrorCodes.TUTE_QUANTITY_BELOW_ALLOCATED,
				"Quantity cannot be lower than the " + allocated + " copies already reserved or dispatched",
				Map.of("allocated", allocated));
	}

	static TuteException versionConflict() {
		return new TuteException(HttpStatus.CONFLICT, ApiErrorCodes.TUTE_VERSION_CONFLICT,
				"This tute was changed by someone else. Reload it and try again.", null);
	}

	static TuteException archived() {
		return new TuteException(HttpStatus.CONFLICT, ApiErrorCodes.TUTE_ARCHIVED, "This tute is archived", null);
	}

	static TuteException invalidState(String message) {
		return new TuteException(HttpStatus.CONFLICT, ApiErrorCodes.TUTE_INVALID_STATE, message, null);
	}

	static TuteException stale(String message, Collection<UUID> ids, Map<String, Object> extra) {
		Map<String, Object> details = new LinkedHashMap<>();
		if (ids != null && !ids.isEmpty()) {
			details.put("fulfillmentIds", ids.stream().map(UUID::toString).toList());
		}
		if (extra != null) {
			details.putAll(extra);
		}
		return new TuteException(HttpStatus.CONFLICT, ApiErrorCodes.TUTE_SELECTION_STALE, message, details);
	}

	static TuteException addressMissing(List<UUID> ids) {
		return new TuteException(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCodes.TUTE_ADDRESS_MISSING,
				ids.size() + " selected tute(s) have no delivery address. Add the address or print without them.",
				Map.of("fulfillmentIds", ids.stream().map(UUID::toString).toList()));
	}

	static TuteException workingSetTooLarge(long count, int max) {
		return new TuteException(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCodes.TUTE_WORKING_SET_TOO_LARGE,
				"There are " + count + " pending postal tutes; Tute Dispatch handles at most " + max
						+ " at once. Filter by course or dispatch some first.",
				Map.of("pendingCount", count, "max", max));
	}

}
