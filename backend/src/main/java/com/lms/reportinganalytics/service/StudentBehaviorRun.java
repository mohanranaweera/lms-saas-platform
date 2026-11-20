package com.lms.reportinganalytics.service;

import java.util.List;
import java.util.UUID;

public record StudentBehaviorRun(StudentBehaviorReport report, List<StudentBehaviorRow> newStudents,
        List<StudentBehaviorRow> droppedStudents, List<StudentBehaviorRow> continuingStudents) {

    public StudentBehaviorRun {
        newStudents = copy(newStudents);
        droppedStudents = copy(droppedStudents);
        continuingStudents = copy(continuingStudents);
    }

    public UUID runId() { return report == null ? null : report.runId(); }

    private static List<StudentBehaviorRow> copy(List<StudentBehaviorRow> rows) {
        return rows == null ? List.of() : List.copyOf(rows);
    }
}
