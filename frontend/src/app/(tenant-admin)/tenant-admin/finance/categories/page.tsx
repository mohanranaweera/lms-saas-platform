"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Plus } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageFinance } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import {
  useExpenseCategories,
  useSaveExpenseCategory,
  useSetCategoryArchived,
  type ExpenseCategory,
} from "@/lib/api/finance";
import { categorySchema, type CategoryFormValues } from "@/lib/validation/finance";

/**
 * Tenant Admin Expense Categories (Wave 7, PAR-23-01). Categories are never
 * deleted (expenses reference them); archiving hides one from new-expense
 * pickers while keeping existing expenses intact. Names are unique per
 * tenant (case-insensitive) — the backend's 409 is surfaced inline.
 */
export default function ExpenseCategoriesPage() {
  const { session } = useAuth();
  const canManage = canManageFinance(session?.role ?? null);
  const [editing, setEditing] = useState<ExpenseCategory | "new" | null>(null);
  const query = useExpenseCategories(true);

  const statusBadge = (row: ExpenseCategory) =>
    row.archived ? <Badge variant="outline">Archived</Badge> : <Badge variant="secondary">Active</Badge>;

  const columns: DataTableColumn<ExpenseCategory>[] = [
    { key: "name", header: "Name", cell: (row) => row.name },
    { key: "description", header: "Description", cell: (row) => row.description ?? "—" },
    { key: "status", header: "Status", cell: statusBadge, hideOnCard: true },
  ];
  if (canManage) {
    columns.push({
      key: "actions",
      header: "Actions",
      hideOnCard: true,
      cell: (row) => <CategoryActions row={row} onRename={() => setEditing(row)} />,
    });
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Expense categories</h1>
          <p className="text-sm text-muted-foreground">
            Categories group expenses in reports. Archive a category you no longer use — it stays on
            past expenses.
          </p>
        </div>
        {canManage && editing === null ? (
          <Button type="button" onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" />
            New category
          </Button>
        ) : null}
      </div>

      {editing !== null ? (
        <CategoryForm
          key={editing === "new" ? "new" : editing.id}
          category={editing === "new" ? null : editing}
          onDone={() => setEditing(null)}
        />
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading expense categories…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "No expense categories yet",
          description: canManage
            ? "Create categories such as Rent, Salaries or Utilities before recording expenses."
            : "Your finance team hasn't created any expense categories yet.",
          action: canManage ? { label: "New category", onClick: () => setEditing("new") } : undefined,
        }}
      >
        {(data) => (
          <DataTable
            columns={columns}
            rows={data}
            rowKey={(row) => row.id}
            caption="Expense categories"
            cardHeading={(row) => row.name}
            cardHeadingAdornment={statusBadge}
            cardFooter={
              canManage
                ? (row) => <CategoryActions row={row} onRename={() => setEditing(row)} />
                : undefined
            }
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}

function CategoryActions({ row, onRename }: { row: ExpenseCategory; onRename: () => void }) {
  const mutation = useSetCategoryArchived();
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" variant="outline" aria-label={`Rename ${row.name}`} onClick={onRename}>
          Rename
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={mutation.isPending}
          aria-label={`${row.archived ? "Restore" : "Archive"} ${row.name}`}
          onClick={async () => {
            setError(null);
            try {
              await mutation.mutateAsync({ id: row.id, archived: !row.archived });
            } catch (e) {
              setError(isApiClientError(e) ? e.message : "Something went wrong. Please try again.");
            }
          }}
        >
          {row.archived ? "Restore" : "Archive"}
        </Button>
      </div>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function CategoryForm({ category, onDone }: { category: ExpenseCategory | null; onDone: () => void }) {
  const mutation = useSaveExpenseCategory();
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CategoryFormValues>({
    resolver: zodResolver(categorySchema),
    defaultValues: { name: category?.name ?? "", description: category?.description ?? "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({
        id: category?.id,
        name: values.name.trim(),
        description: values.description?.trim(),
      });
      onDone();
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.status === 409) {
          setError("name", { type: "server", message: "A category with this name already exists." });
          return;
        }
        const nameError = error.fieldErrors.find((f) => f.field === "name");
        if (nameError) setError("name", { type: "server", message: nameError.message });
        else setPageError(error.message);
        return;
      }
      setPageError("Something went wrong. Please try again.");
    }
  });

  return (
    <form
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
      noValidate
      aria-busy={mutation.isPending}
      aria-labelledby="category-form-heading"
      onSubmit={onSubmit}
    >
      <h2 id="category-form-heading" className="text-base font-semibold text-foreground">
        {category ? `Rename ${category.name}` : "New category"}
      </h2>
      <LiveRegion message={mutation.isPending ? "Saving category…" : ""} />
      {pageError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{pageError}</AlertDescription>
        </Alert>
      ) : null}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="category-name">Name</Label>
          <Input id="category-name" aria-invalid={!!errors.name} {...register("name")} />
          {errors.name ? (
            <p role="alert" className="text-xs text-destructive">
              {errors.name.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="category-description">Description (optional)</Label>
          <Input id="category-description" {...register("description")} />
          {errors.description ? (
            <p role="alert" className="text-xs text-destructive">
              {errors.description.message}
            </p>
          ) : null}
        </div>
      </div>
      <div className="flex gap-2">
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save category"}
        </Button>
        <Button type="button" variant="outline" onClick={onDone} disabled={mutation.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
