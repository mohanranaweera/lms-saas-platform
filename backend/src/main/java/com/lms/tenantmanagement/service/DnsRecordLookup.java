package com.lms.tenantmanagement.service;

import java.util.List;

/**
 * TEN-DOM-01 / ADR-021: a DNS record lookup, behind an interface so tests can
 * supply records without real DNS.
 */
public interface DnsRecordLookup {

	/**
	 * The values of {@code recordType} ({@code CNAME}, {@code TXT}) records at
	 * {@code name}. Empty when the name or the record doesn't exist.
	 * @throws DnsLookupException when the lookup couldn't complete (timeout,
	 * resolver failure) - that says nothing about the records.
	 */
	List<String> lookup(String name, String recordType);

	class DnsLookupException extends RuntimeException {

		public DnsLookupException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
