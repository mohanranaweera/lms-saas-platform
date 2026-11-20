package com.lms.tutemanagement.service;

import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.FulfillmentSource;
import com.lms.tutemanagement.domain.FulfillmentStatus;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.domain.TutePaymentType;
import java.lang.reflect.Constructor;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds synthetic, detached fulfillment rows for pure unit tests. */
final class TuteUnitTestRows {

	private TuteUnitTestRows() {
	}

	static TuteFulfillment row(UUID studentId, UUID courseId, UUID tuteId) {
		try {
			Constructor<TuteFulfillment> constructor = TuteFulfillment.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			TuteFulfillment row = constructor.newInstance();
			ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
			ReflectionTestUtils.setField(row, "studentId", studentId);
			ReflectionTestUtils.setField(row, "courseId", courseId);
			ReflectionTestUtils.setField(row, "tuteId", tuteId);
			ReflectionTestUtils.setField(row, "status", FulfillmentStatus.PENDING);
			ReflectionTestUtils.setField(row, "method", FulfillmentMethod.POSTAL);
			ReflectionTestUtils.setField(row, "source", FulfillmentSource.MANUAL);
			ReflectionTestUtils.setField(row, "tuteName", "Tute");
			ReflectionTestUtils.setField(row, "paymentType", TutePaymentType.FREE);
			ReflectionTestUtils.setField(row, "currency", "LKR");
			return row;
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static TuteFulfillment withOrderDelivery(TuteFulfillment row, String name, String line, String city, String phone) {
		ReflectionTestUtils.setField(row, "orderDeliveryName", name);
		ReflectionTestUtils.setField(row, "orderDeliveryAddressLine", line);
		ReflectionTestUtils.setField(row, "orderDeliveryCity", city);
		ReflectionTestUtils.setField(row, "orderDeliveryPhone", phone);
		return row;
	}

}
