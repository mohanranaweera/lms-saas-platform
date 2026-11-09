package com.lms.tutemanagement.service;

import com.lms.tutemanagement.domain.AddressSource;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.usermanagement.api.StudentDeliveryContact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * TUTE-01 section 6: the delivery address of one fulfillment, resolved at read time until
 * dispatch. Step 1 - the order's delivery snapshot (copied onto the row only when the source
 * order chose postage for the tute's course); step 2 - the student's profile address and phone
 * (tute delivery phone 1, else mobile). Nothing is ever invented: "missing" = blank address
 * line or city.
 */
public record DeliveryAddress(String recipientName, String addressLine, String addressLine2, String city,
		String postalCode, String phone, AddressSource source) {

	public static DeliveryAddress resolve(TuteFulfillment row, StudentDeliveryContact contact) {
		String profileName = contact == null ? null : clean(contact.name());
		String profilePhone = contact == null ? null : clean(contact.phone());
		if (!blank(row.getOrderDeliveryAddressLine()) || !blank(row.getOrderDeliveryCity())) {
			String name = clean(row.getOrderDeliveryName());
			String phone = clean(row.getOrderDeliveryPhone());
			return new DeliveryAddress(name == null ? profileName : name, clean(row.getOrderDeliveryAddressLine()),
					null, clean(row.getOrderDeliveryCity()), null, phone == null ? profilePhone : phone,
					AddressSource.ORDER);
		}
		if (contact == null) {
			return new DeliveryAddress(null, null, null, null, null, null, AddressSource.NONE);
		}
		String line = clean(contact.addressLine());
		String line2 = clean(contact.addressLine2());
		String city = clean(contact.city());
		String postal = clean(contact.postalCode());
		AddressSource source = line == null && line2 == null && city == null && postal == null ? AddressSource.NONE
				: AddressSource.PROFILE;
		return new DeliveryAddress(profileName, line, line2, city, postal, profilePhone, source);
	}

	/** Blank address line or city: the label shows "No address provided"; dispatch is refused. */
	public boolean missing() {
		return addressLine == null || city == null;
	}

	/** The printable address lines (never empty strings). */
	public List<String> lines() {
		List<String> lines = new ArrayList<>();
		if (addressLine != null) {
			lines.add(addressLine);
		}
		if (addressLine2 != null) {
			lines.add(addressLine2);
		}
		String cityLine = city == null ? postalCode : postalCode == null ? city : city + " " + postalCode;
		if (cityLine != null) {
			lines.add(cityLine);
		}
		return lines;
	}

	/**
	 * Normalized address text (case, whitespace and separator punctuation ignored) - two items
	 * of the same student with the same fingerprint share one label, different fingerprints
	 * never merge.
	 */
	public String fingerprint() {
		return normalize(addressLine) + "|" + normalize(addressLine2) + "|" + normalize(city) + "|"
				+ normalize(postalCode);
	}

	/** Stable recipient-group key: student id plus a hash of the normalized address. */
	public static String groupKey(UUID studentId, String fingerprint) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
			return studentId + "-" + HexFormat.of().formatHex(digest, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase(Locale.ROOT).replaceAll("[\\s,.;:]+", " ").strip();
	}

	static String clean(String value) {
		return blank(value) ? null : value.strip();
	}

	static boolean blank(String value) {
		return value == null || value.isBlank();
	}

}
