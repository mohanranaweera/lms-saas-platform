package com.lms.financeexpensemanagement.web.dto;

import java.time.Instant;

/** A short-lived signed receipt URL - the raw storage object key is never exposed. */
public record AttachmentUrlResponse(String url, Instant expiresAt) {

}
