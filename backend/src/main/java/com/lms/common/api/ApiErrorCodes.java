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

	/** MEDIA-INT-01 - the tenant admin has switched this teacher-level provider off. */
	public static final String PROVIDER_DISABLED_BY_TENANT = "PROVIDER_DISABLED_BY_TENANT";

	/** MEDIA-INT-01 - the required provider connection is missing or not ACTIVE. */
	public static final String PROVIDER_NOT_CONNECTED = "PROVIDER_NOT_CONNECTED";

	/** MEDIA-INT-01 - the connected Zoom account is Basic (free); a licensed account is required. */
	public static final String PROVIDER_LICENSE_REQUIRED = "PROVIDER_LICENSE_REQUIRED";

	/** MEDIA-INT-01 - the connection needs to be reconnected (token revoked/expired or scope missing). */
	public static final String PROVIDER_RECONNECT_REQUIRED = "PROVIDER_RECONNECT_REQUIRED";

	/** MEDIA-INT-01 - the provider rejected the supplied API credentials. */
	public static final String PROVIDER_CREDENTIALS_INVALID = "PROVIDER_CREDENTIALS_INVALID";

	/** VIDEO Phase 3 - the YouTube channel is already the live video channel of another tenant (V71). */
	public static final String VIDEO_PROVIDER_CHANNEL_IN_USE = "VIDEO_PROVIDER_CHANNEL_IN_USE";

	/** VIDEO Phase 3 - confirming would replace the tenant's current, different channel without {@code replaceChannel=true}. */
	public static final String VIDEO_PROVIDER_CHANNEL_REPLACEMENT_UNCONFIRMED = "VIDEO_PROVIDER_CHANNEL_REPLACEMENT_UNCONFIRMED";

	/** VIDEO Phase 4 (TD-008) - a Platform Admin switched the tenant's video channel off. */
	public static final String VIDEO_PROVIDER_DISABLED_BY_PLATFORM = "VIDEO_PROVIDER_DISABLED_BY_PLATFORM";

	/** VIDEO Phase 4 - the tenant has no connected video channel to upload to. */
	public static final String VIDEO_PROVIDER_NOT_CONNECTED = "VIDEO_PROVIDER_NOT_CONNECTED";

	/** VIDEO Phase 4 - the tenant's video channel must be reconnected before uploading. */
	public static final String VIDEO_PROVIDER_AUTH_EXPIRED = "VIDEO_PROVIDER_AUTH_EXPIRED";

	/** VIDEO Phase 4 - video uploads are switched off (platform flag or tenant setting). */
	public static final String VIDEO_UPLOADS_DISABLED = "VIDEO_UPLOADS_DISABLED";

	/** VIDEO Phase 4 - the tenant's daily upload budget is used up (Q-014). */
	public static final String UPLOAD_QUOTA_EXCEEDED = "UPLOAD_QUOTA_EXCEEDED";

	/** VIDEO Phase 4 - the uploaded file failed server-side validation (size or content type). */
	public static final String MEDIA_UPLOAD_FAILED = "MEDIA_UPLOAD_FAILED";

	/** VIDEO Phase 5 - the tenant channel was connected without the edit scope (ADR-018 Amendment 3); reconnect. */
	public static final String VIDEO_PROVIDER_SCOPE_MISSING = "VIDEO_PROVIDER_SCOPE_MISSING";

	/** VIDEO Phase 5b - this teacher is not on the institute channel's allowed-teachers list. */
	public static final String VIDEO_UPLOAD_NOT_ALLOWED = "VIDEO_UPLOAD_NOT_ALLOWED";

	/** VIDEO Phase 5 - the media is used by a lesson material and cannot be deleted. */
	public static final String MEDIA_ATTACHED = "MEDIA_ATTACHED";

	/** VIDEO Phase 4 - the upload is not in a state that allows this action. */
	public static final String MEDIA_UPLOAD_STATE_CONFLICT = "MEDIA_UPLOAD_STATE_CONFLICT";

	/** AUTH-PWD-01: an account-setup / password-reset link is invalid, expired, used or from another tenant. */
	public static final String TOKEN_INVALID = "TOKEN_INVALID";

	/** AUTH-PWD-01: the account must change its password before doing anything else. */
	public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

	/** Too many attempts in a time window - try again later. */
	public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

	/** TEN-DOM-01: custom domains are switched off for this tenant (a Platform Admin enables them). */
	public static final String CUSTOM_DOMAINS_DISABLED = "CUSTOM_DOMAINS_DISABLED";

	/** TEN-DOM-01: the hostname is already claimed (by this or another tenant). */
	public static final String CUSTOM_DOMAIN_TAKEN = "CUSTOM_DOMAIN_TAKEN";

	/** TEN-DOM-01: the tenant already holds the maximum number of custom domains. */
	public static final String CUSTOM_DOMAIN_LIMIT_REACHED = "CUSTOM_DOMAIN_LIMIT_REACHED";

	/** Wave 10 - a login from a new device when the student's device slots are all taken (or cooling down). */
	public static final String DEVICE_LIMIT_REACHED = "DEVICE_LIMIT_REACHED";

	/** Wave 10 - the current device is not one of the devices a course-level device limit authorizes. */
	public static final String DEVICE_NOT_AUTHORIZED = "DEVICE_NOT_AUTHORIZED";

	/** Wave 10 - the tenant has not enabled student self-service device management. */
	public static final String DEVICE_SELF_SERVICE_DISABLED = "DEVICE_SELF_SERVICE_DISABLED";

	/** Wave 11 - a broadcast asked for SMS/WhatsApp but the tenant has no usable provider for it. */
	public static final String CHANNEL_NOT_AVAILABLE = "CHANNEL_NOT_AVAILABLE";

	/** Wave 11 - the requested messaging provider adapter is not enabled in this environment. */
	public static final String MESSAGING_PROVIDER_NOT_AVAILABLE = "MESSAGING_PROVIDER_NOT_AVAILABLE";

	private ApiErrorCodes() {
	}

}
