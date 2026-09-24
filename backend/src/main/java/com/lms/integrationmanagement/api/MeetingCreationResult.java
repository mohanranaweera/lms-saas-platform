package com.lms.integrationmanagement.api;

/**
 * The opaque reference a meeting-provider adapter returns when a meeting is
 * created for a {@code class_session} - never a join URL (see {@link
 * LiveClassProviderApi#createMeeting}'s javadoc). Persisted as {@code
 * class_session.provider_reference}; later echoed back on the provider's
 * webhook deliveries and used to mint fresh join/playback URLs on demand.
 */
public record MeetingCreationResult(String providerReference) {

}
