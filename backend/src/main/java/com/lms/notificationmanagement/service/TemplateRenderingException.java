package com.lms.notificationmanagement.service;

/**
 * Thrown by {@link NotificationTemplateRenderer#render} when a template
 * references a {@code {{variable}}} token with no corresponding entry in the
 * rendering-variable map. Per plan §12's "missing-variable case fails closed,
 * not with a raw exception string leaking into the sent email" requirement,
 * this is caught by {@link NotificationDispatchService#dispatchOne} and
 * turned into a {@code FAILED} row - it must never be allowed to produce a
 * partially-rendered or raw-exception-text email body.
 */
public class TemplateRenderingException extends RuntimeException {

	public TemplateRenderingException(String message) {
		super(message);
	}

}
