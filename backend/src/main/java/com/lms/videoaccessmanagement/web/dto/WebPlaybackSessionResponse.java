package com.lms.videoaccessmanagement.web.dto;

import com.lms.videoaccessmanagement.service.PlaybackDescriptor;
import java.time.Instant;
import java.util.UUID;

/** Authenticated WEB playback response; no native/B4 exchange fields. */
public record WebPlaybackSessionResponse(UUID sessionId, Instant expiresAt, PlaybackDescriptor descriptor) {

}
