"use client";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  forgotPasswordSchema,
  type ForgotPasswordFormValues,
} from "@/lib/validation/auth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * Not yet implemented — pending identity-access-service. This form is a disabled
 * placeholder shell only: it never submits and sends no reset email.
 */
export default function ForgotPasswordPage() {
  const { register } = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: "" },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Reset your password</CardTitle>
        <CardDescription>
          We&apos;ll send a reset link to your email address.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p
          id="forgot-password-disabled-notice"
          role="status"
          className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-900/60 dark:bg-amber-950 dark:text-amber-200"
        >
          Not yet implemented — pending identity-access-service.
        </p>
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => event.preventDefault()}
          noValidate
        >
          <fieldset
            disabled
            aria-describedby="forgot-password-disabled-notice"
            className="flex flex-col gap-4"
          >
            <legend className="sr-only">Reset your password</legend>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="forgot-password-email">Email</Label>
              <Input
                id="forgot-password-email"
                type="email"
                autoComplete="email"
                {...register("email")}
              />
            </div>
            <Button type="submit" className="w-full" disabled>
              Send reset link
            </Button>
          </fieldset>
        </form>
        <p className="text-center text-sm text-muted-foreground">
          Remembered your password?{" "}
          <Link href="/login" className="font-medium text-foreground hover:underline">
            Sign in
          </Link>
        </p>
      </CardContent>
    </Card>
  );
}
