package com.lms.tutemanagement.api;

import java.util.List;
import java.util.UUID;

/**
 * TUTE-01 section 7: the label contents of a Tute Dispatch print run, for the PDF renderer in
 * {@code reporting-analytics}. The implementation re-checks {@code TUTE_DISPATCH_PRINT_LABELS}
 * and the caller's course scope, resolves the tenant from the trusted context, revalidates the
 * selection (409 {@code TUTE_SELECTION_STALE} when it is no longer current) and records one audit
 * entry (counts only). Preparing labels never changes any status, stock, payment or notification.
 */
public interface TuteDispatchLabelApi {

	int MAX_FULFILLMENT_IDS = 3000;

	/** Queue filters (all optional); {@code twoPlus} = recipient groups with at least 2 filtered tutes. */
	record DispatchFilter(String search, UUID courseId, UUID tuteId, boolean twoPlus) {
	}

	/**
	 * Either explicit {@code fulfillmentIds} (1..3000) or {@code allFiltered = true} with
	 * {@code filters} and the label count the user confirmed ({@code expectedLabelCount}).
	 */
	record LabelRequest(List<UUID> fulfillmentIds, boolean allFiltered, DispatchFilter filters,
			Integer expectedLabelCount) {
	}

	/** One printed label (one recipient group). */
	record DispatchLabel(String recipientName, List<String> addressLines, boolean addressMissing, String phone,
			List<String> tuteLines, int tuteCount) {
	}

	/** Labels in print order (index number ascending). */
	List<DispatchLabel> prepareLabels(LabelRequest request);

}
