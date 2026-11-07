package com.lms.usermanagement.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * STU-MGMT-02 (spec section 7): the data printed on student QR labels. Requires
 * {@code STUDENTS} VIEW. The ids are re-filtered to the caller's tenant (another
 * tenant's or a removed student's id is silently dropped); at most 500 ids.
 */
public interface StudentQrLabelApi {

	int MAX_LABELS = 500;

	/** Labels sorted by index number. Never carries NIC, contact or credential data. */
	List<StudentQrLabel> findQrLabels(Collection<UUID> studentProfileIds);

	record StudentQrLabel(UUID studentId, String indexNumber, String fullName, String qrPayload) {
	}

}
