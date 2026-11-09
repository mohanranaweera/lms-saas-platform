package com.lms.tutemanagement.service;

import static com.lms.tutemanagement.service.TuteUnitTestRows.row;
import static com.lms.tutemanagement.service.TuteUnitTestRows.withOrderDelivery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.FieldValidationException;
import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchFilter;
import com.lms.tutemanagement.domain.AddressSource;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.service.DispatchProjection.Group;
import com.lms.tutemanagement.service.DispatchProjection.SortKey;
import com.lms.usermanagement.api.StudentDeliveryContact;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TUTE-01 sections 6-7: address resolution, recipient grouping, filters and sorting (synthetic data). */
class DispatchProjectionTest {

	private static final UUID COURSE_A = UUID.randomUUID();

	private static final UUID COURSE_B = UUID.randomUUID();

	private static final UUID TUTE_A = UUID.randomUUID();

	private static final UUID TUTE_B = UUID.randomUUID();

	private static StudentDeliveryContact contact(UUID id, String name, String index, String line, String city,
			String phone) {
		return new StudentDeliveryContact(id, UUID.randomUUID(), name, index, line, null, city, null, phone);
	}

	@Test
	void orderSnapshotWinsOverTheProfileAndMissingMeansNoLineOrNoCity() {
		UUID student = UUID.randomUUID();
		StudentDeliveryContact profile = new StudentDeliveryContact(student, UUID.randomUUID(), "Test Student A", "0042",
				"1 Profile Lane", "Flat 2", "Galle", "80000", "0700000001");
		DeliveryAddress fromProfile = DeliveryAddress.resolve(row(student, COURSE_A, TUTE_A), profile);
		assertThat(fromProfile.source()).isEqualTo(AddressSource.PROFILE);
		assertThat(fromProfile.lines()).containsExactly("1 Profile Lane", "Flat 2", "Galle 80000");
		assertThat(fromProfile.missing()).isFalse();

		DeliveryAddress fromOrder = DeliveryAddress.resolve(
				withOrderDelivery(row(student, COURSE_A, TUTE_A), null, "9 Order Street", "Matara", null), profile);
		assertThat(fromOrder.source()).isEqualTo(AddressSource.ORDER);
		assertThat(fromOrder.lines()).containsExactly("9 Order Street", "Matara");
		assertThat(fromOrder.recipientName()).as("falls back to the profile name").isEqualTo("Test Student A");
		assertThat(fromOrder.phone()).as("falls back to the profile phone").isEqualTo("0700000001");

		StudentDeliveryContact noCity = contact(student, "Test Student A", "0042", "1 Profile Lane", " ", null);
		assertThat(DeliveryAddress.resolve(row(student, COURSE_A, TUTE_A), noCity).missing()).isTrue();
		DeliveryAddress none = DeliveryAddress.resolve(row(student, COURSE_A, TUTE_A), null);
		assertThat(none.missing()).isTrue();
		assertThat(none.source()).isEqualTo(AddressSource.NONE);
	}

	@Test
	void sameStudentAtTwoAddressesIsTwoGroupsAndEquivalentAddressesMerge() {
		UUID student = UUID.randomUUID();
		StudentDeliveryContact profile = contact(student, "Test Student B", "0007", "12, Lake Road", "Kandy", null);
		TuteFulfillment profileRow = row(student, COURSE_A, TUTE_A);
		TuteFulfillment sameAddress = withOrderDelivery(row(student, COURSE_B, TUTE_B), "Test Student B",
				"12 LAKE  ROAD.", "kandy", "0711111111");
		TuteFulfillment otherAddress = withOrderDelivery(row(student, COURSE_B, UUID.randomUUID()), null,
				"5 Hill Street", "Kandy", null);
		List<Group> groups = DispatchProjection.group(List.of(profileRow, sameAddress, otherAddress),
				Map.of(student, profile));
		assertThat(groups).hasSize(2);
		assertThat(groups.get(0).items()).containsExactly(profileRow, sameAddress);
		assertThat(groups.get(0).phone()).isEqualTo("0711111111");
		assertThat(groups.get(1).items()).containsExactly(otherAddress);
		assertThat(groups.get(0).groupKey()).isNotEqualTo(groups.get(1).groupKey()).startsWith(student.toString());
		// Stable: the same input gives the same key.
		assertThat(DispatchProjection.group(List.of(profileRow), Map.of(student, profile)).get(0).groupKey())
			.isEqualTo(groups.get(0).groupKey());
	}

	@Test
	void filtersNarrowItemsAndGroupsTwoPlusCountsFilteredItems() {
		UUID s1 = UUID.randomUUID();
		UUID s2 = UUID.randomUUID();
		Map<UUID, StudentDeliveryContact> contacts = Map.of(s1, contact(s1, "Alpha Student", "0100", "1 Road", "Kandy", null),
				s2, contact(s2, "Beta Student", "0099", "2 Road", "Colombo", null));
		List<Group> groups = DispatchProjection.group(
				List.of(row(s1, COURSE_A, TUTE_A), row(s1, COURSE_B, TUTE_B), row(s2, COURSE_A, TUTE_A)), contacts);

		assertThat(DispatchProjection.filter(groups, new DispatchFilter(null, null, null, true))).hasSize(1);
		List<Group> courseA = DispatchProjection.filter(groups, new DispatchFilter(null, COURSE_A, null, false));
		assertThat(courseA).hasSize(2).allMatch(group -> group.items().size() == 1);
		assertThat(DispatchProjection.filter(groups, new DispatchFilter(null, COURSE_A, null, true)))
			.as("2+ applies to the filtered count").isEmpty();
		assertThat(DispatchProjection.filter(groups, new DispatchFilter(null, null, TUTE_B, false))).hasSize(1);
		assertThat(DispatchProjection.filter(groups, new DispatchFilter("colombo", null, null, false)))
			.extracting(Group::studentId).containsExactly(s2);
		assertThat(DispatchProjection.filter(groups, new DispatchFilter("0100", null, null, false)))
			.extracting(Group::studentId).containsExactly(s1);
		assertThat(DispatchProjection.filter(groups, new DispatchFilter("beta", null, null, false)))
			.extracting(Group::studentId).containsExactly(s2);
	}

	@Test
	void sortsAsStringsKeepingLeadingZerosAndRejectsUnknownKeys() {
		UUID s1 = UUID.randomUUID();
		UUID s2 = UUID.randomUUID();
		UUID s3 = UUID.randomUUID();
		Map<UUID, StudentDeliveryContact> contacts = Map.of(s1, contact(s1, "Charlie", "0084", "1 Road", "Badulla", null),
				s2, contact(s2, "alpha", "123", "2 Road", "Ampara", null), s3, contact(s3, "Bravo", null, null, null, null));
		List<Group> groups = DispatchProjection.group(
				List.of(row(s1, COURSE_A, TUTE_A), row(s2, COURSE_A, TUTE_A), row(s2, COURSE_B, TUTE_B),
						row(s3, COURSE_A, TUTE_A)),
				contacts);
		assertThat(DispatchProjection.sort(groups, SortKey.INDEX_NUMBER, false)).extracting(Group::indexNumber)
			.containsExactly("0084", "123", null);
		assertThat(DispatchProjection.sort(groups, SortKey.INDEX_NUMBER, true)).extracting(Group::indexNumber)
			.containsExactly("123", "0084", null);
		assertThat(DispatchProjection.sort(groups, SortKey.STUDENT_NAME, false)).extracting(Group::studentName)
			.containsExactly("alpha", "Bravo", "Charlie");
		assertThat(DispatchProjection.sort(groups, SortKey.TUTE_COUNT, true).get(0).studentId()).isEqualTo(s2);
		assertThat(DispatchProjection.sort(groups, SortKey.CITY, false)).extracting(Group::city)
			.containsExactly("Ampara", "Badulla", null);
		assertThat(DispatchProjection.parseSort(null)).isEqualTo(SortKey.INDEX_NUMBER);
		assertThatThrownBy(() -> DispatchProjection.parseSort("email")).isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> DispatchProjection.parseDescending("up"))
			.isInstanceOf(FieldValidationException.class);
	}

}
