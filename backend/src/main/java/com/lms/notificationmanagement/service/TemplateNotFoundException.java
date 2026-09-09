package com.lms.notificationmanagement.service;

/**
 * Thrown by {@link NotificationDispatchService#dispatchOne} when no {@code
 * notification_template} row exists for {@code (tenant_id, template_key)} -
 * per plan §13, this row is marked {@code FAILED}; no exception escapes the
 * poller loop. See {@code NotificationTemplateSeedingService}'s javadoc for
 * the known, disclosed gap this can surface for tenants registered before
 * this feature shipped.
 */
public class TemplateNotFoundException extends RuntimeException {

	public TemplateNotFoundException(String message) {
		super(message);
	}

}
