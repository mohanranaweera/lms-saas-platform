package com.lms.identityaccessservice.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of one {@code device} row (Wave 10). Never carries the device
 * token or its hash. {@code current} is true only for the device the calling
 * session runs on.
 */
public record DeviceView(UUID id, String label, String status, Instant firstSeenAt, Instant lastSeenAt,
		String lastIp, Instant revokedAt, String revocationSource, String revokeReason, Instant slotReusableAt,
		boolean current) {

}
