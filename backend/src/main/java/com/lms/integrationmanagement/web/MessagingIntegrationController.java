package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.service.MessagingAccountService;
import com.lms.integrationmanagement.service.MessagingAccountService.MessagingAccountView;
import com.lms.integrationmanagement.service.MessagingAccountService.MessagingSettingsView;
import com.lms.integrationmanagement.service.MessagingAccountService.TestSendResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant SMS / WhatsApp provider accounts (Wave 11). Tenant from the trusted
 * context only; permissions ({@code INTEGRATIONS} VIEW / CREATE_EDIT) are
 * enforced in {@link MessagingAccountService}. No response ever carries a
 * credential - only a masked hint.
 */
@RestController
@RequestMapping("/api/v1/tenant-admin/integrations/messaging")
public class MessagingIntegrationController {

	private final MessagingAccountService service;

	public MessagingIntegrationController(MessagingAccountService service) {
		this.service = service;
	}

	/** {@code credentials} is write-only; {@link #toString()} never renders it. */
	public record ConfigureMessagingRequest(@NotBlank @Size(max = 20) String provider,
			@NotBlank @Size(max = 40) String senderId, Map<String, String> credentials) {

		@Override
		public String toString() {
			return "ConfigureMessagingRequest[provider=" + provider + ", senderId=" + senderId
					+ ", credentials=***]";
		}

	}

	public record TestSendRequest(@NotBlank @Size(max = 20) String destination) {

		@Override
		public String toString() {
			return "TestSendRequest[destination=***]";
		}

	}

	@GetMapping
	public ResponseEntity<ApiResponse<MessagingSettingsView>> settings() {
		return ResponseEntity.ok(ApiResponse.success(service.settings()));
	}

	@PutMapping("/{channel}")
	public ResponseEntity<ApiResponse<MessagingAccountView>> configure(@PathVariable Channel channel,
			@Valid @RequestBody ConfigureMessagingRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
				service.configure(channel, request.provider(), request.senderId(), request.credentials())));
	}

	@PostMapping("/{channel}/disable")
	public ResponseEntity<ApiResponse<MessagingAccountView>> disable(@PathVariable Channel channel) {
		return ResponseEntity.ok(ApiResponse.success(service.disable(channel)));
	}

	@PostMapping("/{channel}/test")
	public ResponseEntity<ApiResponse<TestSendResult>> test(@PathVariable Channel channel,
			@Valid @RequestBody TestSendRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.testSend(channel, request.destination())));
	}

}
