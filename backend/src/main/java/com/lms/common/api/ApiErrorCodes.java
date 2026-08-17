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

	private ApiErrorCodes() {
	}

}
