package com.lms.videoaccessmanagement.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code media_upload_job.status} (V72 - VIDEO Phase 4, plan §13):
 *
 * <pre>
 * AWAITING_PARTS -&gt; QUEUED -&gt; TRANSFERRING -&gt; COMPLETED
 *                              TRANSFERRING -&gt; RETRY_WAIT | QUOTA_PAUSED | PARKED_RECONNECT -&gt; (QUEUED) -&gt; TRANSFERRING
 *    any live state -&gt; FAILED | CANCELLED
 * </pre>
 */
public enum MediaUploadJobStatus {

	/** The browser is uploading parts to S3 staging. */
	AWAITING_PARTS,
	/** Staged and validated; waiting for a worker. */
	QUEUED,
	/** A worker is streaming staging → provider. */
	TRANSFERRING,
	/** A transient failure; retried at {@code next_attempt_at}. */
	RETRY_WAIT,
	/** The provider's daily upload quota is used up; resumes after it resets. */
	QUOTA_PAUSED,
	/** The channel needs reconnecting or is switched off; resumes once it is usable again. */
	PARKED_RECONNECT,
	COMPLETED, FAILED, CANCELLED;

	private static final Set<MediaUploadJobStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED);

	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}

	/** Waiting for its turn in the worker queue. */
	public boolean isWaitingForWorker() {
		return this == QUEUED || this == RETRY_WAIT || this == QUOTA_PAUSED;
	}

}
