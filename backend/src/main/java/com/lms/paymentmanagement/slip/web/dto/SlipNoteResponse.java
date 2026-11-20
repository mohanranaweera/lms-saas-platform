package com.lms.paymentmanagement.slip.web.dto;

import com.lms.paymentmanagement.slip.service.SlipNoteView;
import java.time.Instant;
import java.util.UUID;

/** PAY-HUB-01: one staff note on a payment slip; {@code authorEmail} is nullable. */
public record SlipNoteResponse(UUID id, UUID slipId, String body, UUID authorUserId, String authorEmail,
		Instant createdAt) {

	public static SlipNoteResponse from(SlipNoteView view) {
		return new SlipNoteResponse(view.id(), view.slipId(), view.body(), view.authorUserId(), view.authorEmail(),
				view.createdAt());
	}

}
