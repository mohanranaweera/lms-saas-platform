package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * From-address configuration for outbound email, mirroring {@link
 * PaymentGatewayProperties}'s minimal env-var-backed shape. SMTP
 * host/port/username/password are standard Spring Boot {@code
 * spring.mail.*} properties, auto-bound by {@code spring-boot-starter-mail}
 * to the {@code JavaMailSender} bean - this class exists only for the one
 * property Spring Boot does not provide out of the box: the {@code From}
 * address to stamp on outgoing mail, so it isn't hardcoded in {@link
 * com.lms.integrationmanagement.mail.SmtpMessagingProviderApi}.
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

	private String fromAddress = "no-reply@example.com";

	public String getFromAddress() {
		return fromAddress;
	}

	public void setFromAddress(String fromAddress) {
		this.fromAddress = fromAddress;
	}

}
