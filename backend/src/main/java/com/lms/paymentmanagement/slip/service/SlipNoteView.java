package com.lms.paymentmanagement.slip.service;

import java.time.Instant;
import java.util.UUID;

/** PAY-HUB-01: one staff note on a slip. {@code authorEmail} is {@code null} if the author no longer resolves. */
public record SlipNoteView(UUID id, UUID slipId, String body, UUID authorUserId, String authorEmail,
		Instant createdAt) {

}
