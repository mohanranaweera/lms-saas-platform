package com.lms.integrationmanagement.mail;

import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.integrationmanagement.config.MailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real, working SMTP-backed implementation of {@link MessagingProviderApi},
 * standing in for a not-yet-selected commercial email provider - the same
 * "real, working, in-process integration" precedent {@code
 * FakePaymentGatewayAdapter} follows for the payment gateway, not {@code
 * UnavailableObjectStorageApi}'s fail-loud-no-fallback precedent. SMTP is a
 * protocol, not a vendor, so there is nothing to defer selecting: this
 * adapter sends real mail via Spring's auto-configured {@link
 * JavaMailSender} (bound from standard {@code spring.mail.*} properties by
 * {@code spring-boot-starter-mail}).
 *
 * <p>Any {@link org.springframework.mail.MailException} thrown by the
 * underlying sender propagates uncaught - see {@link MessagingProviderApi}'s
 * javadoc for why this method must not be called inside an open database
 * transaction, and why failures are not swallowed here.
 */
@Component
public class SmtpMessagingProviderApi implements MessagingProviderApi {

	private final JavaMailSender javaMailSender;
	private final MailProperties mailProperties;

	public SmtpMessagingProviderApi(JavaMailSender javaMailSender, MailProperties mailProperties) {
		this.javaMailSender = javaMailSender;
		this.mailProperties = mailProperties;
	}

	@Override
	public void sendEmail(String toEmail, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(mailProperties.getFromAddress());
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(body);
		javaMailSender.send(message);
	}

}
