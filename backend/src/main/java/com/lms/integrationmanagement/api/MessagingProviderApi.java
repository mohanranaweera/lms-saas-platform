package com.lms.integrationmanagement.api;

/**
 * The sole cross-module contract for sending email (mirrors {@link
 * ObjectStorageApi}'s role for object storage). {@code
 * notification-management} calls this interface to send email, and must
 * never touch SMTP/{@code JavaMailSender} directly - {@code
 * integration-management} owns all third-party credentials, including mail
 * provider configuration, per {@code .claude/rules/architecture.md}.
 *
 * <p>Implementations may throw an unchecked exception (e.g. Spring's {@code
 * MailException}) if the send fails; per {@code .claude/rules/backend.md}'s
 * "do not span a transaction across an outbound call" rule, this method must
 * never be called from within an open database transaction. The caller (the
 * notification dispatch poller) is responsible for catching any exception
 * per-row and marking that row {@code FAILED} - this method does not swallow
 * or wrap send failures itself.
 */
public interface MessagingProviderApi {

	void sendEmail(String toEmail, String subject, String body);

}
