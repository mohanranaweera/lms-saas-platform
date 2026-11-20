package com.lms.exammanagement.service.questionbank;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reader (no dependency): comma separated, fields optionally enclosed in
 * double quotes, {@code ""} inside a quoted field is one quote, and quoted fields may
 * contain commas and line breaks. Records end at CRLF, LF or CR. A quote appearing inside
 * an unquoted field is kept literally (lenient, as spreadsheet exports do). Text is
 * expected to be already decoded (the caller handles UTF-8 and the optional BOM).
 */
final class CsvReader {

	/** A parsed record with its 1-based record number (the header is record 1). */
	record CsvRecord(int number, List<String> cells) {

		boolean isBlank() {
			return cells.stream().allMatch(cell -> cell.isBlank());
		}

	}

	static final class CsvFormatException extends Exception {

		private final int recordNumber;

		CsvFormatException(int recordNumber, String message) {
			super(message);
			this.recordNumber = recordNumber;
		}

		int recordNumber() {
			return recordNumber;
		}

	}

	private CsvReader() {
	}

	static List<CsvRecord> read(String text) throws CsvFormatException {
		List<CsvRecord> records = new ArrayList<>();
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean inQuotes = false;
		boolean recordHasContent = false;
		int recordNumber = 1;
		int length = text.length();
		int i = 0;
		while (i < length) {
			char c = text.charAt(i);
			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < length && text.charAt(i + 1) == '"') {
						cell.append('"');
						i += 2;
						continue;
					}
					inQuotes = false;
					i++;
					// Only a separator or the end of the record may follow a closing quote.
					if (i < length) {
						char next = text.charAt(i);
						if (next != ',' && next != '\r' && next != '\n') {
							throw new CsvFormatException(recordNumber,
									"Unexpected text after a closing quote - put quotes around the whole cell");
						}
					}
					continue;
				}
				cell.append(c);
				i++;
				continue;
			}
			if (c == '"' && cell.isEmpty()) {
				inQuotes = true;
				recordHasContent = true;
				i++;
				continue;
			}
			if (c == ',') {
				cells.add(cell.toString());
				cell.setLength(0);
				recordHasContent = true;
				i++;
				continue;
			}
			if (c == '\r' || c == '\n') {
				cells.add(cell.toString());
				cell.setLength(0);
				records.add(new CsvRecord(recordNumber, List.copyOf(cells)));
				cells.clear();
				recordHasContent = false;
				recordNumber++;
				i += (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') ? 2 : 1;
				continue;
			}
			cell.append(c);
			recordHasContent = true;
			i++;
		}
		if (inQuotes) {
			throw new CsvFormatException(recordNumber, "A quoted cell is never closed");
		}
		if (recordHasContent || !cell.isEmpty()) {
			cells.add(cell.toString());
			records.add(new CsvRecord(recordNumber, List.copyOf(cells)));
		}
		return records;
	}

}
