package com.lms.tenantmanagement.web.dto;

import com.lms.tenantmanagement.service.CustomDomainService.CustomDomainView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TEN-DOM-01: one custom domain. {@code dnsRecords} are the two records the
 * tenant creates (CNAME, TXT); the CNAME value is null while the platform has
 * no target configured.
 */
public record CustomDomainResponse(UUID id, String hostname, String status, boolean primary,
		List<DnsRecordResponse> dnsRecords, Instant verifiedAt, Instant lastCheckedAt, String lastError,
		String suspendedReason, Instant createdAt) {

	public record DnsRecordResponse(String type, String name, String value) {

	}

	public static CustomDomainResponse from(CustomDomainView view) {
		return new CustomDomainResponse(view.id(), view.hostname(), view.status().name(), view.primary(),
				view.dnsRecords()
					.stream()
					.map(record -> new DnsRecordResponse(record.type(), record.name(), record.value()))
					.toList(),
				view.verifiedAt(), view.lastCheckedAt(), view.lastError(), view.suspendedReason(), view.createdAt());
	}

}
