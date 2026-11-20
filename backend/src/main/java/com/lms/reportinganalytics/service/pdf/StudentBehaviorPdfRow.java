package com.lms.reportinganalytics.service.pdf;

/** Display-only Jasper bean row; classification already happened in service. */
public final class StudentBehaviorPdfRow {
    private final String category;
    private final String rowNo;
    private final String indexNumber;
    private final String studentName;
    private final String mobile;

    public StudentBehaviorPdfRow(String category, String rowNo, String indexNumber, String studentName, String mobile) {
        this.category = category;
        this.rowNo = rowNo;
        this.indexNumber = indexNumber;
        this.studentName = studentName;
        this.mobile = mobile;
    }

    public String getCategory() { return category; }
    public String getRowNo() { return rowNo; }
    public String getIndexNumber() { return indexNumber; }
    public String getStudentName() { return studentName; }
    public String getMobile() { return mobile; }
}
