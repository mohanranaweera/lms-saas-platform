import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { FieldError } from "@/lib/api/types";

interface ErrorStateProps {
  message: string;
  /** The backend `ApiError.code` (e.g. "VALIDATION_ERROR"), when the failure came from the API client. */
  code?: string;
  /** Per-field validation messages from the backend's `ApiError.fieldErrors`, when applicable. */
  fieldErrors?: FieldError[];
  onRetry?: () => void;
  className?: string;
}

/**
 * `role="alert"` ensures assistive tech announces the failure immediately, without
 * the user needing to navigate to the error text.
 */
export function ErrorState({
  message,
  code,
  fieldErrors,
  onRetry,
  className,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-6 py-12 text-center",
        className
      )}
    >
      <AlertCircle className="size-6 text-destructive" aria-hidden="true" />
      <p className="text-sm text-destructive">{message}</p>
      {code ? (
        <p className="text-xs text-destructive/70">Error code: {code}</p>
      ) : null}
      {fieldErrors && fieldErrors.length > 0 ? (
        <ul className="flex flex-col gap-1 text-left text-xs text-destructive">
          {fieldErrors.map((fieldError) => (
            <li key={fieldError.field}>
              <span className="font-medium">{fieldError.field}:</span>{" "}
              {fieldError.message}
            </li>
          ))}
        </ul>
      ) : null}
      {onRetry ? (
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  );
}
