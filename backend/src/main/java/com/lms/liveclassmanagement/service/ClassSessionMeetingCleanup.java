package com.lms.liveclassmanagement.service;

import com.lms.integrationmanagement.api.LiveClassProviderApi;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Deletes the provider meeting of a cancelled class session AFTER the cancel
 * has committed (MEDIA-INT-01) - never inside the cancel transaction
 * ({@code .claude/rules/backend.md}), and best-effort: the provider adapter
 * never throws from {@code cancelMeeting}. Runs synchronously on the request
 * thread, so the request's tenant context is still in place.
 */
@Component
public class ClassSessionMeetingCleanup {

	/** Published by {@code ClassSessionService#cancelSession} for a session that had a meeting. */
	public record MeetingCancelled(UUID sessionId, UUID providerConnectionId, String providerReference) {

	}

	private final LiveClassProviderApi liveClassProviderApi;

	public ClassSessionMeetingCleanup(LiveClassProviderApi liveClassProviderApi) {
		this.liveClassProviderApi = liveClassProviderApi;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMeetingCancelled(MeetingCancelled event) {
		liveClassProviderApi.cancelMeeting(event.providerConnectionId(), event.providerReference());
	}

}
