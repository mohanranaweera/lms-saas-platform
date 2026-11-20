package com.lms.integrationmanagement.gateway.media;

/**
 * A failed call to a media/meeting provider (MEDIA-INT-01). The message is
 * always a short, client-safe description written by us - never the
 * provider's response body, which can echo tokens or keys.
 */
public class MediaProviderException extends RuntimeException {

	public enum Kind {

		/** The provider rejected our credentials (401/403, {@code invalid_grant}) - reconnect needed. */
		AUTH_REJECTED,

		/** The addressed resource (meeting, library) does not exist at the provider. */
		NOT_FOUND,

		/** The provider refused the request for another reason (4xx). */
		REJECTED,

		/** Network failure, timeout, 5xx or an unreadable response - try again later. */
		UNAVAILABLE

	}

	private final Kind kind;

	public MediaProviderException(Kind kind, String safeMessage) {
		super(safeMessage);
		this.kind = kind;
	}

	public Kind getKind() {
		return kind;
	}

}
