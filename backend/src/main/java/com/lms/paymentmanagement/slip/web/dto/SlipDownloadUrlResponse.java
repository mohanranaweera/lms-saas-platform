package com.lms.paymentmanagement.slip.web.dto;

import java.time.Instant;

/**
 * {@code contentType} (PAY-HUB-01) is one of {@code image/png}, {@code image/jpeg},
 * {@code image/gif}, {@code application/pdf}, or {@code null} when storage cannot
 * report it - the client then offers a download only.
 */
public record SlipDownloadUrlResponse(String url, Instant expiresAt, String contentType) {

	public SlipDownloadUrlResponse(String url, Instant expiresAt) {
		this(url, expiresAt, null);
	}

}
