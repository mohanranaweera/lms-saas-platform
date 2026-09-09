package com.lms.notificationmanagement.service;

/**
 * Thrown by {@link NotificationDispatchService#dispatchOne} when a claimed
 * outbox row's {@code recipientUserId} does not resolve to a live {@code
 * tenant_user} row via {@code UserProvisioningApi#findTenantUserSummaries}
 * (deleted/never-provisioned account) - per plan §13, this row is marked
 * {@code FAILED}; no exception escapes the poller loop.
 */
public class RecipientNotResolvedException extends RuntimeException {

	public RecipientNotResolvedException(String message) {
		super(message);
	}

}
