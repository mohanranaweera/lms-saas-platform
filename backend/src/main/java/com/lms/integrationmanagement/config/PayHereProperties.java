package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code payment.payhere.*} (PAY-GW-01). Merchant credentials are NOT here -
 * they are per tenant, encrypted in {@code tenant_payment_gateway_account}.
 *
 * <ul>
 * <li>{@code notify-url}: the publicly reachable URL of
 * {@code POST /api/v1/integrations/webhooks/payhere}. PayHere cannot reach
 * localhost - locally this needs a tunnel (e.g. ngrok). Empty = online
 * payment initiation fails closed (503).</li>
 * <li>{@code return-url-template}: the tenant storefront origin, with
 * {@code {subdomain}} substituted from the tenant's own record (never from
 * request input), e.g. {@code https://{subdomain}.example.com}.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "payment.payhere")
public class PayHereProperties {

	private String sandboxCheckoutUrl = "https://sandbox.payhere.lk/pay/checkout";

	private String liveCheckoutUrl = "https://www.payhere.lk/pay/checkout";

	private String notifyUrl = "";

	private String returnUrlTemplate = "";

	public String getSandboxCheckoutUrl() {
		return sandboxCheckoutUrl;
	}

	public void setSandboxCheckoutUrl(String sandboxCheckoutUrl) {
		this.sandboxCheckoutUrl = sandboxCheckoutUrl;
	}

	public String getLiveCheckoutUrl() {
		return liveCheckoutUrl;
	}

	public void setLiveCheckoutUrl(String liveCheckoutUrl) {
		this.liveCheckoutUrl = liveCheckoutUrl;
	}

	public String getNotifyUrl() {
		return notifyUrl;
	}

	public void setNotifyUrl(String notifyUrl) {
		this.notifyUrl = notifyUrl;
	}

	public String getReturnUrlTemplate() {
		return returnUrlTemplate;
	}

	public void setReturnUrlTemplate(String returnUrlTemplate) {
		this.returnUrlTemplate = returnUrlTemplate;
	}

}
