package com.lms.identityaccessservice.domain;

/**
 * Shared by {@code device_session.status} (V5) and {@code
 * platform_admin_session.status} (V6) - both use the identical
 * {@code CHECK (status IN ('active','revoked','expired'))} vocabulary.
 * Rotation is an in-place update, not a status change (see V5's header
 * comment) - only login (ACTIVE), logout (REVOKED), and a future expiry
 * sweep (EXPIRED) change this value.
 */
public enum SessionStatus {

	ACTIVE, REVOKED, EXPIRED

}
