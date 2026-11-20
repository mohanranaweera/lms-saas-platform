package com.lms.usermanagement.api;

import java.util.UUID;

/**
 * TUTE-01: the postal-delivery contact of one student, keyed by the cross-domain {@code userId}
 * other modules store as {@code studentId}. {@code indexNumber} is a string (leading zeros are
 * significant). {@code phone} is the profile's tute delivery phone 1, else the mobile number.
 * Read-only; never the {@code StudentProfile} entity.
 *
 * @param studentProfileId the profile id the staff {@code /students/{id}} pages address.
 */
public record StudentDeliveryContact(UUID userId, UUID studentProfileId, String name, String indexNumber,
		String addressLine, String addressLine2, String city, String postalCode, String phone) {

}
