package com.lms.paymentmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.integrationmanagement.gateway.WebhookSignatureVerifier;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.paymentmanagement.order.web.dto.OrderCreateRequest;
import com.lms.paymentmanagement.order.web.dto.OrderPaymentStatusResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import com.lms.paymentmanagement.payment.web.dto.RefundCreateRequest;
import com.lms.paymentmanagement.payment.web.dto.RefundResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for MVP-010 (Order and Payment
 * Foundation)'s integration tests, mirroring {@code
 * CourseManagementTestSupport}'s established technique exactly. Extends it
 * (rather than {@code AuthIntegrationTestSupport} directly) since every
 * order needs a real, published, priced course to snapshot from.
 */
public abstract class PaymentManagementTestSupport extends CourseManagementTestSupport {

	/** Must match {@code src/test/resources/application.yml}'s {@code payment.gateway.webhook-secret}. */
	protected static final String TEST_WEBHOOK_SECRET = "test-only-webhook-secret-not-for-production-use";

	// ------------------------------------------------------------------
	// Orders.
	// ------------------------------------------------------------------

	protected HttpResult<OrderResponse> createOrder(String host, String token, UUID courseId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new OrderCreateRequest(courseId)));
		return parseSingle(perform(authenticated(builder, host, token)), OrderResponse.class);
	}

	protected OrderResponse createOrderOrFail(String host, String token, UUID courseId) {
		HttpResult<OrderResponse> result = createOrder(host, token, courseId);
		if (result.getStatusCode() != org.springframework.http.HttpStatus.CREATED) {
			throw new IllegalStateException("Order creation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	/**
	 * Posts an arbitrary raw JSON body (rather than serializing {@link
	 * OrderCreateRequest}) - used to prove, at the real HTTP/deserialization
	 * layer, that extra client-supplied fields ({@code price}/{@code amount}/
	 * {@code tenantId}/{@code studentId}) are silently dropped by {@code
	 * @JsonIgnoreProperties(ignoreUnknown = true)}, never trusted.
	 */
	protected HttpResult<OrderResponse> createOrderWithRawBody(String host, String token, String rawBody) {
		MockHttpServletRequestBuilder builder = post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
			.content(rawBody);
		return parseSingle(perform(authenticated(builder, host, token)), OrderResponse.class);
	}

	protected HttpResult<OrderResponse> getOrder(String host, String token, UUID orderId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/orders/{id}", orderId);
		return parseSingle(perform(authenticated(builder, host, token)), OrderResponse.class);
	}

	protected HttpResult<OrderPaymentStatusResponse> getOrderPaymentStatus(String host, String token, UUID orderId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/orders/{id}/payment-status", orderId);
		return parseSingle(perform(authenticated(builder, host, token)), OrderPaymentStatusResponse.class);
	}

	protected HttpResult<PaymentInitiationResponse> initiatePayment(String host, String token, UUID orderId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/orders/{id}/payments", orderId);
		return parseSingle(perform(authenticated(builder, host, token)), PaymentInitiationResponse.class);
	}

	protected PaymentInitiationResponse initiatePaymentOrFail(String host, String token, UUID orderId) {
		HttpResult<PaymentInitiationResponse> result = initiatePayment(host, token, orderId);
		if (result.getStatusCode() != org.springframework.http.HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Payment initiation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	// ------------------------------------------------------------------
	// Payments / refunds.
	// ------------------------------------------------------------------

	protected HttpResult<PaymentResponse> getPayment(String host, String token, UUID paymentId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payments/{id}", paymentId);
		return parseSingle(perform(authenticated(builder, host, token)), PaymentResponse.class);
	}

	protected HttpResult<RefundResponse> createRefund(String host, String token, UUID paymentId, BigDecimal amount,
			String reason) {
		return createRefund(host, token, paymentId, amount, reason, null);
	}

	protected HttpResult<RefundResponse> createRefund(String host, String token, UUID paymentId, BigDecimal amount,
			String reason, UUID idempotencyKey) {
		MockHttpServletRequestBuilder builder = post("/api/v1/payments/{id}/refunds", paymentId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new RefundCreateRequest(amount, reason, idempotencyKey)));
		return parseSingle(perform(authenticated(builder, host, token)), RefundResponse.class);
	}

	protected HttpResult<List<RefundResponse>> listRefunds(String host, String token, UUID paymentId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payments/{id}/refunds", paymentId);
		return parseList(perform(authenticated(builder, host, token)), RefundResponse.class);
	}

	// ------------------------------------------------------------------
	// Ledger.
	// ------------------------------------------------------------------

	protected HttpResult<List<LedgerHistoryEntryResponse>> getLedgerHistory(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/ledger/history");
		return parseList(perform(authenticated(builder, host, token)), LedgerHistoryEntryResponse.class);
	}

	protected HttpResult<PageResponse<LedgerHistoryEntryResponse>> getLedgerDashboard(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/ledger/dashboard");
		return parsePage(perform(authenticated(builder, host, token)), LedgerHistoryEntryResponse.class);
	}

	// ------------------------------------------------------------------
	// Webhook (no Host header, no auth - permitAll + signature-verified).
	// ------------------------------------------------------------------

	protected HttpResult<Void> sendPaymentWebhook(String gatewayReference, boolean confirmed) {
		String body = webhookBody(gatewayReference, confirmed);
		String signature = WebhookSignatureVerifier.sign(body, TEST_WEBHOOK_SECRET);
		return sendRawPaymentWebhook(body, signature);
	}

	protected HttpResult<Void> sendRawPaymentWebhook(String rawBody, String signature) {
		MockHttpServletRequestBuilder builder = post("/api/v1/integrations/webhooks/payment")
			.contentType(MediaType.APPLICATION_JSON)
			.content(rawBody);
		if (signature != null) {
			builder.header("X-Payment-Signature", signature);
		}
		return parseSingle(perform(builder), Void.class);
	}

	protected String webhookBody(String gatewayReference, boolean confirmed) {
		return "{\"gatewayReference\":\"" + gatewayReference + "\",\"confirmed\":" + confirmed + "}";
	}

	protected MockHttpServletRequestBuilder withHost(MockHttpServletRequestBuilder builder, String host) {
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		return builder;
	}

}
