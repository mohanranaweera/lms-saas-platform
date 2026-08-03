"use client";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { loginSchema, type LoginFormValues } from "@/lib/validation/auth";
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
 * placeholder shell only: it never submits, sets no session/token, and has no
 * reachable "successful login" client state. Real authentication is a future,
 * separately planned module.
 */
export default function LoginPage() {
  const { register } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Sign in</CardTitle>
        <CardDescription>Access your LMS Platform account.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p
          id="login-disabled-notice"
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
            aria-describedby="login-disabled-notice"
            className="flex flex-col gap-4"
          >
            <legend className="sr-only">Sign in</legend>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="login-email">Email</Label>
              <Input
                id="login-email"
                type="email"
                autoComplete="email"
                {...register("email")}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="login-password">Password</Label>
              <Input
                id="login-password"
                type="password"
                autoComplete="current-password"
                {...register("password")}
              />
            </div>
            <Button type="submit" className="w-full" disabled>
              Sign in
            </Button>
          </fieldset>
        </form>
        <p className="text-center text-sm text-muted-foreground">
          Don&apos;t have an account?{" "}
          <Link href="/register" className="font-medium text-foreground hover:underline">
            Create one
          </Link>
        </p>
      </CardContent>
    </Card>
  );
}
