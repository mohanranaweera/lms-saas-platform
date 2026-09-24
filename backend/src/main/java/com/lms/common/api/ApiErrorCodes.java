package com.lms.common.api;

/** Stable machine-readable error codes carried in {@link ApiError#code()}. */
public final class ApiErrorCodes {

	public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

	public static final String NOT_FOUND = "NOT_FOUND";

	public static final String CONFLICT = "CONFLICT";

	public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

	public static final String FORBIDDEN = "FORBIDDEN";

	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	/**
	 * Generic invalid-credentials response (AUTH-1): covers both "no such
	 * user" and "wrong password", including a wrong password against a
	 * suspended account (password is verified before suspension is checked,
	 * so a wrong password never distinguishes a suspended account from a
	 * nonexistent one) - anti-enumeration by design, never subdivided.
	 */
	public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

	/** Tenant could not be resolved from the subdomain, or is suspended/cancelled - generic, no distinction. */
	public static final String TENANT_UNAVAILABLE = "TENANT_UNAVAILABLE";

	/** The specific tenant_user/platform_admin_user row is suspended - only reachable after password verification succeeds. */
	public static final String USER_SUSPENDED = "USER_SUSPENDED";

	/** Refresh token not found / revoked / expired / already rotated - generic, never distinguished in the response. */
	public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";

	/** A revoked/expired session presented via an otherwise-valid access token. */
	public static final String SESSION_REVOKED = "SESSION_REVOKED";

	/** A role code supplied to a provisioning call does not match any real {@code Role} enum value. */
	public static final String INVALID_ROLE_CODE = "INVALID_ROLE_CODE";

	/** A dependency the request needs (e.g. object storage) is not configured/reachable. */
	public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

	/** An uploaded file exceeds the maximum allowed size. */
	public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

	/** An uploaded file's declared or sniffed content type is not on the accepted allow-list. */
	public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

	/** The requested route exists but does not support the HTTP method used (e.g. PUT/PATCH/DELETE on a GET-only resource). */
	public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

	/**
	 * An audit log write's {@code actorId} does not resolve to a known
	 * {@code tenant_user}/{@code platform_admin_user} row (see {@code
	 * auditlogmanagement.service.AuditLogService#requireKnownActor}) - a
	 * genuine internal-integrity inconsistency (5xx), distinguished from the
	 * generic {@link #INTERNAL_ERROR} purely for log/metric triage.
	 */
	public static final String UNKNOWN_AUDIT_ACTOR = "UNKNOWN_AUDIT_ACTOR";

	/** Wave 5 (PAR-06-03) - {@code material.available_from_at} is in the future for this caller. */
	public static final String MATERIAL_NOT_YET_AVAILABLE = "MATERIAL_NOT_YET_AVAILABLE";

	/** Wave 5 (PAR-06-03) - {@code material.expiry_at} has passed for this caller. */
	public static final String MATERIAL_EXPIRED = "MATERIAL_EXPIRED";

	/** Wave 5 (PAR-06-03) - {@code material.max_downloads} has already been reached for this material. */
	public static final String DOWNLOAD_LIMIT_REACHED = "DOWNLOAD_LIMIT_REACHED";

	/** Wave 5 (PAR-20-02) - a seek/jump beyond {@code furthest_position_seconds} while {@code allow_seeking = false}. */
	public static final String SEEK_NOT_ALLOWED = "SEEK_NOT_ALLOWED";

	/**
	 * Wave 5 (PAR-17-01/PAR-20-02) - a {@code video_watch_session} was just
	 * revoked (device-fingerprint mismatch, or a max-watch-duration breach)
	 * as a direct result of the request that triggered this response.
	 */
	public static final String POLICY_VIOLATION = "POLICY_VIOLATION";

	/**
	 * Wave 5 (PAR-20-01/PAR-20-02) - the supplied video playback token failed
	 * signature/expiry/{@code jti}-to-session validation, or the session it
	 * names is no longer {@code ACTIVE}.
	 */
	public static final String PLAYBACK_TOKEN_INVALID = "PLAYBACK_TOKEN_INVALID";

	private ApiErrorCodes() {
	}

}
