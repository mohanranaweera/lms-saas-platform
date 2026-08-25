package com.lms.paymentmanagement.slip.web.dto;

import java.time.Instant;

public record SlipDownloadUrlResponse(String url, Instant expiresAt) {

}
