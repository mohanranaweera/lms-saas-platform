package com.lms.tutemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchFilter;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.usermanagement.api.StudentDeliveryContact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * TUTE-01 section 7: the ONE server projection behind queue rows, counts, labels and dispatch
 * confirmation - pure, so grouping, filtering and sorting are unit-testable. A recipient group is
 * (student, normalized delivery address): the same student at two addresses is two groups and
 * two labels, never merged.
 */
public final class DispatchProjection {

	public enum SortKey {

		INDEX_NUMBER, STUDENT_NAME, TUTE_COUNT, CITY

	}

	private DispatchProjection() {
	}

	/** One recipient group; {@code address} is the first item's resolved address. */
	public record Group(String groupKey, UUID studentId, StudentDeliveryContact contact, DeliveryAddress address,
			String phone, List<TuteFulfillment> items) {

		public String studentName() {
			if (address.recipientName() != null) {
				return address.recipientName();
			}
			return contact == null ? null : contact.name();
		}

		public String indexNumber() {
			return contact == null ? null : contact.indexNumber();
		}

		public String city() {
			return address.city();
		}

		Group withItems(List<TuteFulfillment> subset) {
			return new Group(groupKey, studentId, contact, address, phone, List.copyOf(subset));
		}

	}

	/**
	 * Groups {@code rows} (in their given order) by student and address fingerprint; group order
	 * follows the first item of each group.
	 */
	public static List<Group> group(List<TuteFulfillment> rows, Map<UUID, StudentDeliveryContact> contacts) {
		Map<String, List<TuteFulfillment>> itemsByKey = new LinkedHashMap<>();
		Map<String, DeliveryAddress> addressByKey = new LinkedHashMap<>();
		Map<String, String> phoneByKey = new LinkedHashMap<>();
		for (TuteFulfillment row : rows) {
			DeliveryAddress address = DeliveryAddress.resolve(row, contacts.get(row.getStudentId()));
			String key = DeliveryAddress.groupKey(row.getStudentId(), address.fingerprint());
			itemsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
			addressByKey.putIfAbsent(key, address);
			if (address.phone() != null) {
				phoneByKey.putIfAbsent(key, address.phone());
			}
		}
		List<Group> groups = new ArrayList<>();
		itemsByKey.forEach((key, items) -> {
			UUID studentId = items.get(0).getStudentId();
			groups.add(new Group(key, studentId, contacts.get(studentId), addressByKey.get(key), phoneByKey.get(key),
					List.copyOf(items)));
		});
		return groups;
	}

	/**
	 * Course / tute filters narrow each group's items (a group without matching items drops out);
	 * search (name, index number or city, case-insensitive) narrows groups; {@code twoPlus} keeps
	 * groups with at least two filtered items.
	 */
	public static List<Group> filter(List<Group> groups, DispatchFilter filter) {
		if (filter == null) {
			return groups;
		}
		String needle = filter.search() == null ? "" : filter.search().strip().toLowerCase(Locale.ROOT);
		List<Group> result = new ArrayList<>();
		for (Group group : groups) {
			List<TuteFulfillment> items = group.items()
				.stream()
				.filter(item -> filter.courseId() == null || filter.courseId().equals(item.getCourseId()))
				.filter(item -> filter.tuteId() == null || filter.tuteId().equals(item.getTuteId()))
				.toList();
			if (items.isEmpty()) {
				continue;
			}
			if (!needle.isEmpty() && !contains(group.studentName(), needle) && !contains(group.indexNumber(), needle)
					&& !contains(group.city(), needle)) {
				continue;
			}
			if (filter.twoPlus() && items.size() < 2) {
				continue;
			}
			result.add(items.size() == group.items().size() ? group : group.withItems(items));
		}
		return result;
	}

	public static List<Group> sort(List<Group> groups, SortKey key, boolean descending) {
		Comparator<Group> primary = switch (key) {
			case INDEX_NUMBER -> nullsLast(Group::indexNumber, Comparator.naturalOrder(), descending);
			case STUDENT_NAME -> nullsLast(Group::studentName, String.CASE_INSENSITIVE_ORDER, descending);
			case CITY -> nullsLast(Group::city, String.CASE_INSENSITIVE_ORDER, descending);
			case TUTE_COUNT -> {
				Comparator<Group> count = Comparator.comparingInt(group -> group.items().size());
				yield descending ? count.reversed() : count;
			}
		};
		Comparator<Group> ordered = primary
			.thenComparing(nullsLast(Group::studentName, String.CASE_INSENSITIVE_ORDER, false))
			.thenComparing(Group::groupKey);
		return groups.stream().sorted(ordered).toList();
	}

	/** {@code null}/blank sort parameters default to index number ascending; anything else is 400. */
	public static SortKey parseSort(String sort) {
		if (sort == null || sort.isBlank()) {
			return SortKey.INDEX_NUMBER;
		}
		return switch (sort.strip()) {
			case "indexNumber" -> SortKey.INDEX_NUMBER;
			case "studentName" -> SortKey.STUDENT_NAME;
			case "tuteCount" -> SortKey.TUTE_COUNT;
			case "city" -> SortKey.CITY;
			default -> throw new FieldValidationException("The request is not valid",
					List.of(new FieldError("sort", "Sort by indexNumber, studentName, tuteCount or city")));
		};
	}

	public static boolean parseDescending(String direction) {
		if (direction == null || direction.isBlank() || "asc".equalsIgnoreCase(direction.strip())) {
			return false;
		}
		if ("desc".equalsIgnoreCase(direction.strip())) {
			return true;
		}
		throw new FieldValidationException("The request is not valid",
				List.of(new FieldError("direction", "Direction is asc or desc")));
	}

	private static <T> Comparator<Group> nullsLast(Function<Group, T> getter, Comparator<T> comparator,
			boolean descending) {
		Comparator<T> directed = descending ? comparator.reversed() : comparator;
		return Comparator.comparing(group -> blankToNull(getter.apply(group)), Comparator.nullsLast(directed));
	}

	private static <T> T blankToNull(T value) {
		if (value instanceof String text && text.isBlank()) {
			return null;
		}
		return value;
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

}
