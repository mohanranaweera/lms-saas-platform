package com.lms.liveclassmanagement.web.dto;

import java.time.Instant;

/** A freshly-minted, short-lived recording-playback URL - never a stable/reusable link. */
public record ClassSessionRecordingResponse(String playbackUrl, Instant expiresAt) {

}
