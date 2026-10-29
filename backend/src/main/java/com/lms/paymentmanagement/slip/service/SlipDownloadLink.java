package com.lms.paymentmanagement.slip.service;

import java.time.Instant;

/**
 * A short-lived signed slip URL plus, when known, the content type recorded
 * at upload ({@code null} when storage cannot report one) - PAY-HUB-01.
 */
public record SlipDownloadLink(String url, Instant expiresAt, String contentType) {

}
