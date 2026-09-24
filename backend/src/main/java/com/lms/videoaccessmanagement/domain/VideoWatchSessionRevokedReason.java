package com.lms.videoaccessmanagement.domain;

/**
 * {@code video_watch_session.revoked_reason} (V51, Wave 5, PAR-17-01/
 * PAR-20-02). {@link #EXPIRED} is defined to match V51's {@code
 * ck_video_watch_session_revoked_reason} CHECK constraint but is not
 * persisted by any code path in this wave (there is no scheduled expiry
 * sweep job yet - the {@code expiresAt} column is enforced only at token
 * -validation time by {@code PlaybackTokenService}/{@code
 * VideoPlaybackSessionService}, per plan §11's deferred scope). {@link
 * #ENDED} likewise only ever appears here as a reserved value for a future
 * administrative "force end" action - the graceful, student-initiated
 * {@code VideoPlaybackSessionService#endPlaybackSession} sets the session's
 * {@code status} straight to {@link VideoWatchSessionStatus#ENDED} without
 * populating {@code revoked_reason} at all, since a voluntary end is not a
 * revocation - see that method's own javadoc.
 */
public enum VideoWatchSessionRevokedReason {

	EXPIRED, SUPERSEDED_BY_NEW_SESSION, ENDED, POLICY_VIOLATION

}
