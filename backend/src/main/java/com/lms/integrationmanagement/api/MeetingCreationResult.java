package com.lms.integrationmanagement.api;

import java.util.UUID;

/**
 * The opaque reference a meeting-provider adapter returns when a meeting is
 * created for a {@code class_session} - never a join URL (see {@link
 * LiveClassProviderApi#createMeeting}'s javadoc). Persisted as {@code
 * class_session.provider_reference}; later echoed back on the provider's
 * webhook deliveries and used to mint fresh join/playback URLs on demand.
 * {@code connectionId} is the teacher's Zoom connection that hosts the
 * meeting (MEDIA-INT-01), or {@code null} for the fake provider.
 */
public record MeetingCreationResult(String providerReference, UUID connectionId) {

}
