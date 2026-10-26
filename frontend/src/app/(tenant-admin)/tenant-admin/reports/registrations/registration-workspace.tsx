"use client";

import { useMemo, useRef, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ChevronLeft, ChevronRight, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { ReportRunError } from "@/components/reports/report-run-error";
import { isApiClientError } from "@/lib/api/error";
import { useDownloadReportPdf } from "@/lib/api/reports";
import {
  registrationPdfPath,
  useCreateRegistrationRun,
  useRegistrationRows,
  type RegistrationOptions,
  type RegistrationReport,
  type RegistrationRowsPage,
} from "@/lib/api/registration-report";
import {
  DEFAULT_REGISTRATION_FILTER,
  registrationFilterSchema,
  registrationRequestKey,
  toRegistrationRunRequest,
  type RegistrationFilterValues,
} from "@/lib/validation/registration-report";
import { RegistrationResults } from "./registration-results";

const ALL = "__all__";
const WEEKDAYS = ["S", "M", "T", "W", "T", "F", "S"];
const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

export function RegistrationWorkspace({
  options,
}: {
  options: RegistrationOptions;
}) {
  const initialRange = useMemo(() => currentMonthRange(), []);
  const mutation = useCreateRegistrationRun();
  const download = useDownloadReportPdf();
  const [report, setReport] = useState<RegistrationReport | null>(null);
  const [lastKey, setLastKey] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [shownRows, setShownRows] = useState<RegistrationRowsPage | null>(null);
  const [expired, setExpired] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const [calendarMonth, setCalendarMonth] = useState(
    initialRange.startDate.slice(0, 7),
  );
  const tokenRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const {
    control,
    handleSubmit,
    register,
    reset,
    setError,
    setValue,
    formState: { errors },
  } = useForm<RegistrationFilterValues>({
    resolver: zodResolver(registrationFilterSchema),
    defaultValues: { ...DEFAULT_REGISTRATION_FILTER, ...initialRange },
  });
  const watched = useWatch({ control });
  const values: RegistrationFilterValues = {
    ...DEFAULT_REGISTRATION_FILTER,
    ...initialRange,
    ...watched,
  };
  const request = toRegistrationRunRequest(values);
  const stale = !!report && lastKey !== registrationRequestKey(request);
  const rowsQuery = useRegistrationRows(report?.runId ?? null, page);
  const rows =
    rowsQuery.isSuccess && !rowsQuery.isPlaceholderData
      ? rowsQuery.data
      : shownRows;
  const dayCount = inclusiveDayCount(values.startDate, values.endDate);
  const cells = useMemo(() => calendarCells(calendarMonth), [calendarMonth]);
  const [calendarYear, calendarMonthNumber] = calendarMonth
    .split("-")
    .map(Number);
  const yearOptions = calendarYears(
    calendarYear,
    values.startDate,
    values.endDate,
  );

  const selectDate = (date: string) => {
    if (!values.startDate || values.endDate) {
      setValue("startDate", date, { shouldDirty: true, shouldValidate: true });
      setValue("endDate", "", { shouldDirty: true, shouldValidate: false });
      return;
    }
    if (date < values.startDate) {
      setValue("endDate", values.startDate, {
        shouldDirty: true,
        shouldValidate: true,
      });
      setValue("startDate", date, { shouldDirty: true, shouldValidate: true });
    } else {
      setValue("endDate", date, { shouldDirty: true, shouldValidate: true });
    }
  };

  const run = async (form: RegistrationFilterValues) => {
    const body = toRegistrationRunRequest(form);
    const token = ++tokenRef.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setAnnouncement("Generating report…");
    try {
      const next = await mutation.mutateAsync({
        request: body,
        signal: controller.signal,
      });
      if (token !== tokenRef.current) return;
      setReport(next);
      setLastKey(registrationRequestKey(body));
      setPage(0);
      setShownRows(null);
      setExpired(false);
      setAnnouncement(
        next.totalRegistrations === 0
          ? "No registrations match these filters."
          : "Registration report ready.",
      );
    } catch (error) {
      if (token !== tokenRef.current) return;
      if (isApiClientError(error) && error.status === 422) {
        for (const item of error.fieldErrors) {
          if (item.field === "startDate" || item.field === "endDate") {
            setError(item.field, { type: "server", message: item.message });
          }
        }
      }
      setAnnouncement("Registration report could not be generated.");
    }
  };

  const cancel = () => {
    tokenRef.current++;
    abortRef.current?.abort();
    reset({ ...DEFAULT_REGISTRATION_FILTER, ...initialRange });
    setCalendarMonth(initialRange.startDate.slice(0, 7));
    mutation.reset();
    setReport(null);
    setShownRows(null);
    setLastKey(null);
    setAnnouncement("Filters cleared.");
  };

  const exportPdf = async () => {
    if (!report || stale || expired) return;
    try {
      await download.mutateAsync({
        path: registrationPdfPath(report.runId),
        filename: "registration-report.pdf",
      });
    } catch (error) {
      if (isApiClientError(error) && error.status === 404) setExpired(true);
    }
  };

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(18rem,22rem)_1fr]">
      <LiveRegion message={announcement} />
      <form
        noValidate
        onSubmit={(event) => void handleSubmit(run)(event)}
        aria-label="Registration report filters"
        className="flex flex-col gap-5 self-start rounded-lg border p-4"
      >
        <div>
          <Label htmlFor="registration-course">Course</Label>
          <Controller
            control={control}
            name="courseId"
            render={({ field }) => (
              <Select
                value={field.value || ALL}
                onValueChange={(value) =>
                  field.onChange(value === ALL ? "" : value)
                }
              >
                <SelectTrigger id="registration-course" className="mt-1 w-full">
                  <SelectValue>
                    {(value: string) =>
                      value === ALL
                        ? "All Courses"
                        : (options.courses.find((course) => course.id === value)
                            ?.name ?? "All Courses")
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>All Courses</SelectItem>
                  {options.courses.map((course) => (
                    <SelectItem key={course.id} value={course.id}>
                      {course.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </div>
        <div>
          <Label htmlFor="registration-operator">Operator</Label>
          <Controller
            control={control}
            name="operatorId"
            render={({ field }) => (
              <Select
                value={field.value || ALL}
                onValueChange={(value) =>
                  field.onChange(value === ALL ? "" : value)
                }
              >
                <SelectTrigger
                  id="registration-operator"
                  className="mt-1 w-full"
                >
                  <SelectValue>
                    {(value: string) =>
                      value === ALL
                        ? "All operators"
                        : (options.operators.find(
                            (operator) => operator.id === value,
                          )?.name ?? "All operators")
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>All operators</SelectItem>
                  {options.operators.map((operator) => (
                    <SelectItem key={operator.id} value={operator.id}>
                      {operator.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </div>
        <div>
          <Label htmlFor="registration-method">Registration Method</Label>
          <Controller
            control={control}
            name="registrationMethod"
            render={({ field }) => (
              <Select
                value={field.value || ALL}
                onValueChange={(value) =>
                  field.onChange(value === ALL ? "" : value)
                }
              >
                <SelectTrigger id="registration-method" className="mt-1 w-full">
                  <SelectValue>
                    {(value: string) =>
                      value === ALL ? "All methods" : methodLabel(value)
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>All methods</SelectItem>
                  {options.registrationMethods.map((method) => (
                    <SelectItem key={method} value={method}>
                      {methodLabel(method)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </div>
        <fieldset className="border-0 p-0">
          <legend className="mb-3 text-sm font-medium uppercase tracking-[0.2em] text-muted-foreground">
            Select date
          </legend>
          <div className="rounded-sm bg-[#2f5bd8] px-5 py-4 text-white">
            <p className="text-3xl font-medium tabular-nums">
              {dayCount} selected
            </p>
          </div>
          <div className="mt-4 p-1">
            <div
              className="mb-3 flex items-center justify-between gap-2"
              aria-live="polite"
            >
              <div className="flex items-center gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Previous month"
                  onClick={() => setCalendarMonth(addMonths(calendarMonth, -1))}
                >
                  <ChevronLeft aria-hidden="true" />
                </Button>
                <Select
                  value={String(calendarMonthNumber)}
                  onValueChange={(month) =>
                    setCalendarMonth(
                      toCalendarMonth(calendarYear, Number(month)),
                    )
                  }
                >
                  <SelectTrigger
                    aria-label="Calendar month"
                    className="w-20 border-0 bg-transparent font-medium shadow-none"
                  >
                    <SelectValue>
                      {(month: string) =>
                        MONTHS[Number(month) - 1]?.slice(0, 3).toUpperCase()
                      }
                    </SelectValue>
                  </SelectTrigger>
                  <SelectContent>
                    {MONTHS.map((name, index) => (
                      <SelectItem key={name} value={String(index + 1)}>
                        {name.slice(0, 3).toUpperCase()}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Next month"
                  onClick={() => setCalendarMonth(addMonths(calendarMonth, 1))}
                >
                  <ChevronRight aria-hidden="true" />
                </Button>
              </div>
              <div className="flex items-center gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Previous year"
                  onClick={() =>
                    setCalendarMonth(
                      toCalendarMonth(calendarYear - 1, calendarMonthNumber),
                    )
                  }
                >
                  <ChevronLeft aria-hidden="true" />
                </Button>
                <Select
                  value={String(calendarYear)}
                  onValueChange={(year) =>
                    setCalendarMonth(
                      toCalendarMonth(Number(year), calendarMonthNumber),
                    )
                  }
                >
                  <SelectTrigger
                    aria-label="Calendar year"
                    className="w-20 border-0 bg-transparent font-medium tabular-nums shadow-none"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {yearOptions.map((year) => (
                      <SelectItem key={year} value={String(year)}>
                        {year}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Next year"
                  onClick={() =>
                    setCalendarMonth(
                      toCalendarMonth(calendarYear + 1, calendarMonthNumber),
                    )
                  }
                >
                  <ChevronRight aria-hidden="true" />
                </Button>
              </div>
            </div>
            <div className="grid grid-cols-7 gap-1 text-center text-xs text-muted-foreground">
              {WEEKDAYS.map((day, index) => (
                <span key={`${day}-${index}`} className="py-1 font-medium">
                  {day}
                </span>
              ))}
            </div>
            <div
              className="mt-1 grid grid-cols-7 gap-1"
              role="group"
              aria-label={`${monthLabel(calendarMonth)} dates`}
            >
              {cells.map((cell) =>
                cell.date ? (
                  <CalendarDay
                    key={cell.key}
                    date={cell.date}
                    startDate={values.startDate}
                    endDate={values.endDate}
                    onSelect={selectDate}
                  />
                ) : (
                  <span key={cell.key} aria-hidden="true" className="h-8" />
                ),
              )}
            </div>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
            <div>
              <Label htmlFor="registration-start">Start date</Label>
              <input
                id="registration-start"
                type="date"
                className="mt-1 h-9 w-full rounded-md border bg-transparent px-2"
                {...register("startDate")}
              />
            </div>
            <div>
              <Label htmlFor="registration-end">End date</Label>
              <input
                id="registration-end"
                type="date"
                className="mt-1 h-9 w-full rounded-md border bg-transparent px-2"
                {...register("endDate")}
              />
            </div>
          </div>
          {errors.startDate ? (
            <p role="alert" className="mt-1 text-xs text-destructive">
              {errors.startDate.message}
            </p>
          ) : null}
          {errors.endDate ? (
            <p role="alert" className="mt-1 text-xs text-destructive">
              {errors.endDate.message}
            </p>
          ) : null}
        </fieldset>
        <div className="flex justify-end gap-2 border-t pt-4">
          <Button type="button" variant="outline" onClick={cancel}>
            Cancel
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            <Search aria-hidden="true" />
            {mutation.isPending ? "Searching…" : "Search"}
          </Button>
        </div>
      </form>
      <section aria-label="Report results" aria-busy={mutation.isPending}>
        {mutation.isPending ? (
          <LoadingState label="Generating registration report…" />
        ) : mutation.isError && !report ? (
          <ReportRunError
            error={mutation.error}
            narrowHint="Try a shorter date range"
            fieldLabels={{
              startDate: "Start date",
              endDate: "End date",
              courseIds: "Course",
              operatorId: "Operator",
              registrationMethod: "Registration Method",
              body: "Filter",
            }}
            onRetry={() => void handleSubmit(run)()}
          />
        ) : !report ? (
          <EmptyState
            title="Choose registration filters"
            description="The current month is selected. Optionally filter by course, operator, or registration method, then choose Search."
          />
        ) : report.totalRegistrations === 0 ? (
          <EmptyState
            title="No registrations match your filters"
            description="Choose an earlier date range or clear optional filters. Registration dates use the date students joined this institute."
            action={{
              label: "Change filters",
              onClick: () =>
                document.getElementById("registration-start")?.focus(),
            }}
          />
        ) : (
          <RegistrationResults
            report={report}
            rows={rows}
            loading={rowsQuery.isFetching}
            error={rowsQuery.isError}
            stale={stale}
            expired={expired}
            downloading={download.isPending}
            onExport={() => void exportPdf()}
            onRunAgain={() => void handleSubmit(run)()}
            onRetry={() => void rowsQuery.refetch()}
            onPageChange={(nextPage) => {
              setShownRows(rowsQuery.data ?? null);
              setPage(nextPage);
            }}
          />
        )}
      </section>
    </div>
  );
}

function CalendarDay({
  date,
  startDate,
  endDate,
  onSelect,
}: {
  date: string;
  startDate: string;
  endDate: string;
  onSelect: (date: string) => void;
}) {
  const selected = date === startDate || date === endDate;
  const inRange =
    !!startDate && !!endDate && date > startDate && date < endDate;
  return (
    <button
      type="button"
      aria-label={date}
      aria-pressed={selected}
      onClick={() => onSelect(date)}
      className={`h-8 rounded-md text-sm tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
        selected
          ? "bg-[#2f5bd8] font-semibold text-white"
          : inRange
            ? "bg-[#e7edff] text-[#234bb3]"
            : "hover:bg-muted"
      }`}
    >
      {Number(date.slice(-2))}
    </button>
  );
}

function currentMonthRange() {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  const monthKey = `${year}-${String(month).padStart(2, "0")}`;
  const lastDay = new Date(year, month, 0).getDate();
  return {
    startDate: `${monthKey}-01`,
    endDate: `${monthKey}-${String(lastDay).padStart(2, "0")}`,
  };
}

function addMonths(value: string, amount: number) {
  const [year, month] = value.split("-").map(Number);
  const next = new Date(Date.UTC(year, month - 1 + amount, 1));
  return `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, "0")}`;
}

function monthLabel(value: string) {
  const [year, month] = value.split("-").map(Number);
  return `${MONTHS[month - 1]} ${year}`;
}

function toCalendarMonth(year: number, month: number) {
  return `${year}-${String(month).padStart(2, "0")}`;
}

function calendarYears(
  visibleYear: number,
  startDate: string,
  endDate: string,
) {
  const currentYear = new Date().getFullYear();
  const selectedYears = [startDate, endDate]
    .filter(Boolean)
    .map((date) => Number(date.slice(0, 4)));
  const minimum = Math.min(
    currentYear - 10,
    visibleYear - 10,
    ...selectedYears,
  );
  const maximum = Math.max(
    currentYear + 10,
    visibleYear + 10,
    ...selectedYears,
  );
  return Array.from(
    { length: maximum - minimum + 1 },
    (_, index) => minimum + index,
  );
}

function calendarCells(value: string) {
  const [year, month] = value.split("-").map(Number);
  const leading = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
  const total = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return Array.from({ length: leading + total }, (_, index) => {
    if (index < leading) return { key: `${value}-blank-${index}`, date: null };
    return {
      key: `${value}-${index - leading + 1}`,
      date: `${value}-${String(index - leading + 1).padStart(2, "0")}`,
    };
  });
}

function inclusiveDayCount(start: string, end: string) {
  if (!start || !end || start > end) return 0;
  return (
    Math.floor(
      (Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) /
        86_400_000,
    ) + 1
  );
}

function methodLabel(method: string) {
  return method === "SELF_REGISTERED"
    ? "Self registered"
    : method === "ADMIN_CREATED"
      ? "Admin created"
      : method;
}
