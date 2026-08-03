"use client";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { registerSchema, type RegisterFormValues } from "@/lib/validation/auth";
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
 * placeholder shell only: it never submits, creates no account, and has no
 * reachable "successful registration" client state.
 */
export default function RegisterPage() {
  const { register } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { fullName: "", email: "", password: "", confirmPassword: "" },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Create an account</CardTitle>
        <CardDescription>Set up access to LMS Platform.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p
          id="register-disabled-notice"
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
            aria-describedby="register-disabled-notice"
            className="flex flex-col gap-4"
          >
            <legend className="sr-only">Create an account</legend>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="register-full-name">Full name</Label>
              <Input
                id="register-full-name"
                autoComplete="name"
                {...register("fullName")}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="register-email">Email</Label>
              <Input
                id="register-email"
                type="email"
                autoComplete="email"
                {...register("email")}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="register-password">Password</Label>
              <Input
                id="register-password"
                type="password"
                autoComplete="new-password"
                {...register("password")}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="register-confirm-password">Confirm password</Label>
              <Input
                id="register-confirm-password"
                type="password"
                autoComplete="new-password"
                {...register("confirmPassword")}
              />
            </div>
            <Button type="submit" className="w-full" disabled>
              Create account
            </Button>
          </fieldset>
        </form>
        <p className="text-center text-sm text-muted-foreground">
          Already have an account?{" "}
          <Link href="/login" className="font-medium text-foreground hover:underline">
            Sign in
          </Link>
        </p>
      </CardContent>
    </Card>
  );
}
