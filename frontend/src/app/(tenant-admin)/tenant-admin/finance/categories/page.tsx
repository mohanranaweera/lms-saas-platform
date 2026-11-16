"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Plus, Tag } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Switch } from "@/components/ui/switch";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FinanceHubFrame } from "@/components/finance/finance-tabs";
import { useFinanceGates } from "@/components/finance/book/shared";
import { isApiClientError } from "@/lib/api/error";
import {
  useExpenseCategories,
  useSaveExpenseCategory,
  useSetCategoryArchived,
  type ExpenseCategory,
} from "@/lib/api/finance";
import {
  categorySchema,
  type CategoryFormValues,
} from "@/lib/validation/finance";

/**
 * FIN-01 Categories tab (Klass parity: category cards). Categories are shared
 * by expenses and account-book income/expense postings. They are never
 * deleted (postings reference them): Klass's "Delete" is Archive here, which
 * hides a category from new pickers and keeps it on past records. Names are
 * unique per tenant (case-insensitive) - the backend's 409 is shown inline.
 */
export default function ExpenseCategoriesPage() {
  const gates = useFinanceGates();
  const [editing, setEditing] = useState<ExpenseCategory | "new" | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const query = useExpenseCategories(true);

  return (
    <FinanceHubFrame
      title="Categories"
      description="Group income and expenses in reports. Archive a category you no longer use - it stays on past records."
      actions={
        gates.categoryAdd && editing === null ? (
          <Button type="button" onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" />
            New category
          </Button>
        ) : null
      }
    >
      {editing !== null ? (
        <CategoryForm
          key={editing === "new" ? "new" : editing.id}
          category={editing === "new" ? null : editing}
          onDone={() => setEditing(null)}
        />
      ) : null}

      <div className="flex items-center gap-2">
        <Switch
          id="categories-show-archived"
          checked={showArchived}
          onCheckedChange={setShowArchived}
        />
        <Label htmlFor="categories-show-archived">
          Show archived categories
        </Label>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading categories…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "No categories yet",
          description: gates.categoryAdd
            ? "Create categories such as Rent, Salaries or Class fees before recording transactions."
            : "Your finance team hasn't created any categories yet.",
          action: gates.categoryAdd
            ? { label: "New category", onClick: () => setEditing("new") }
            : undefined,
        }}
      >
        {(data) => {
          const visible = data.filter((c) => showArchived || !c.archived);
          if (visible.length === 0) {
            return (
              <p className="text-sm text-muted-foreground">
                All categories are archived. Turn on &quot;Show archived
                categories&quot; to see them.
              </p>
            );
          }
          return (
            <ul
              className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3"
              aria-label="Categories"
            >
              {visible.map((row) => (
                <li key={row.id}>
                  <Card
                    className="h-full"
                    data-testid={`category-card-${row.id}`}
                  >
                    <CardContent className="flex items-start gap-3">
                      <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                        <Tag className="size-5" aria-hidden="true" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <h3 className="truncate text-base font-semibold text-foreground">
                          {row.name}
                        </h3>
                        <p className="truncate text-sm text-muted-foreground">
                          {row.description ?? "Transaction category"}
                        </p>
                      </div>
                      {row.archived ? (
                        <Badge variant="outline">Archived</Badge>
                      ) : null}
                    </CardContent>
                    {gates.categoryAdd || gates.categoryArchive ? (
                      <CardFooter className="flex justify-end border-t border-border py-2">
                        <CategoryActions
                          row={row}
                          canRename={gates.categoryAdd}
                          canArchive={gates.categoryArchive}
                          onRename={() => setEditing(row)}
                        />
                      </CardFooter>
                    ) : null}
                  </Card>
                </li>
              ))}
            </ul>
          );
        }}
      </QueryStateBoundary>
    </FinanceHubFrame>
  );
}

function CategoryActions({
  row,
  canRename,
  canArchive,
  onRename,
}: {
  row: ExpenseCategory;
  canRename: boolean;
  canArchive: boolean;
  onRename: () => void;
}) {
  const mutation = useSetCategoryArchived();
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap gap-2">
        {canRename && !row.archived ? (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            aria-label={`Rename ${row.name}`}
            onClick={onRename}
          >
            Rename
          </Button>
        ) : null}
        {canArchive ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={mutation.isPending}
            aria-label={`${row.archived ? "Restore" : "Archive"} ${row.name}`}
            onClick={async () => {
              setError(null);
              try {
                await mutation.mutateAsync({
                  id: row.id,
                  archived: !row.archived,
                });
              } catch (e) {
                setError(
                  isApiClientError(e)
                    ? e.message
                    : "Something went wrong. Please try again.",
                );
              }
            }}
          >
            {row.archived ? "Restore" : "Archive"}
          </Button>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function CategoryForm({
  category,
  onDone,
}: {
  category: ExpenseCategory | null;
  onDone: () => void;
}) {
  const mutation = useSaveExpenseCategory();
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CategoryFormValues>({
    resolver: zodResolver(categorySchema),
    defaultValues: {
      name: category?.name ?? "",
      description: category?.description ?? "",
    },
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
          setError("name", {
            type: "server",
            message: "A category with this name already exists.",
          });
          return;
        }
        const nameError = error.fieldErrors.find((f) => f.field === "name");
        if (nameError)
          setError("name", { type: "server", message: nameError.message });
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
      <h2
        id="category-form-heading"
        className="text-base font-semibold text-foreground"
      >
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
          <Input
            id="category-name"
            aria-invalid={!!errors.name}
            {...register("name")}
          />
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
        <Button
          type="button"
          variant="outline"
          onClick={onDone}
          disabled={mutation.isPending}
        >
          Cancel
        </Button>
      </div>
    </form>
  );
}
