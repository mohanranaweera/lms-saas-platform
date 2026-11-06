package com.lms.reportinganalytics.service.pdf;

/**
 * One band of the CAL-01 timetable PDF. {@code kind} selects the detail band:
 * {@code GRID_HEAD} (weekday header of the month grid), {@code WEEK} (one
 * Sunday-first grid week, {@code cell1..cell7} as styled text), {@code
 * AGENDA_TITLE} (appendix heading), {@code DATE} (agenda date heading),
 * {@code ITEM} (one occurrence) and {@code EMPTY} (no classes). Display-only
 * strings; the template does no logic beyond choosing the band.
 */
public record TimetablePdfRow(String kind, String cell1, String cell2, String cell3, String cell4, String cell5,
		String cell6, String cell7, String text, String time, String course, String teachers, String status) {

	static TimetablePdfRow of(String kind, String text) {
		return new TimetablePdfRow(kind, "", "", "", "", "", "", "", text, "", "", "", "");
	}

	static TimetablePdfRow week(String[] cells) {
		return new TimetablePdfRow(TimetablePdfRenderer.WEEK, cells[0], cells[1], cells[2], cells[3], cells[4],
				cells[5], cells[6], "", "", "", "", "");
	}

	static TimetablePdfRow item(String time, String course, String teachers, String status) {
		return new TimetablePdfRow(TimetablePdfRenderer.ITEM, "", "", "", "", "", "", "", "", time, course, teachers,
				status);
	}

	public String getKind() { return kind; }
	public String getCell1() { return cell1; }
	public String getCell2() { return cell2; }
	public String getCell3() { return cell3; }
	public String getCell4() { return cell4; }
	public String getCell5() { return cell5; }
	public String getCell6() { return cell6; }
	public String getCell7() { return cell7; }
	public String getText() { return text; }
	public String getTime() { return time; }
	public String getCourse() { return course; }
	public String getTeachers() { return teachers; }
	public String getStatus() { return status; }

}
