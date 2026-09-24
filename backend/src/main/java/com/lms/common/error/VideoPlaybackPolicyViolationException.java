package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * A {@code video_watch_session} was just revoked, as a direct result of the
 * request that triggered this response, because of a policy violation
 * (device-fingerprint mismatch, or a {@code max_watch_duration_seconds}
 * breach) - Wave 5, PAR-20-02/.claude/rules/security.md's IP/device-anomaly
 * revocation requirement. The triggering request is rejected; no partial
 * progress is persisted alongside the revocation.
 */
public class VideoPlaybackPolicyViolationException extends ApplicationException {

	public VideoPlaybackPolicyViolationException(String message) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.POLICY_VIOLATION, message);
	}

}
