import { z } from "zod";

/**
 * STU-MGMT-02 create/edit student form (spec §3). Client validation is UX
 * only; the backend re-validates everything and its field errors are mapped
 * back onto these fields.
 */

const PHONE = /^[0-9+ ()-]*$/;

function optionalText(max: number, label: string) {
  return z.string().trim().max(max, `${label} must be ${max} characters or fewer.`);
}

function optionalPhone(max: number, label: string) {
  return optionalText(max, label).refine((value) => PHONE.test(value), {
    message: `${label} may contain only digits, spaces, +, ( ) and -.`,
  });
}

function todayIso(): string {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

const baseShape = {
  email: z
    .string()
    .trim()
    .min(1, "Email is required.")
    .email("Enter a valid email address.")
    .max(255, "Email must be 255 characters or fewer."),
  firstName: z.string().trim().min(1, "First name is required.").max(120, "First name must be 120 characters or fewer."),
  lastName: z.string().trim().min(1, "Last name is required.").max(120, "Last name must be 120 characters or fewer."),
  whatsappNumber: optionalPhone(50, "WhatsApp number"),
  dateOfBirth: z
    .string()
    .trim()
    .refine((value) => value === "" || /^\d{4}-\d{2}-\d{2}$/.test(value), "Enter a valid date.")
    .refine((value) => value === "" || value >= "1900-01-01", "Date of birth must be on or after 1900-01-01.")
    .refine((value) => value === "" || value <= todayIso(), "Date of birth can't be in the future."),
  mobile: z
    .string()
    .trim()
    .min(1, "Mobile number is required.")
    .max(50, "Mobile number must be 50 characters or fewer.")
    .refine((value) => PHONE.test(value), "Mobile number may contain only digits, spaces, +, ( ) and -."),
  addressLine: optionalText(255, "Address line 1"),
  addressLine2: optionalText(255, "Address line 2"),
  city: optionalText(100, "City"),
  postalCode: optionalText(20, "Postal code"),
  guardianName: optionalText(255, "Guardian name"),
  guardianPhone: optionalPhone(50, "Guardian contact number"),
  guardianNic: optionalText(20, "Guardian NIC"),
  fixedPhone: optionalPhone(50, "Home telephone"),
  school: optionalText(255, "School"),
  alYear: z
    .string()
    .trim()
    .refine((value) => value === "" || /^\d{4}$/.test(value), "Enter a 4-digit year.")
    .refine(
      (value) => value === "" || (Number(value) >= 2000 && Number(value) <= 2100),
      "A/L year must be between 2000 and 2100."
    ),
  stream: optionalText(50, "A/L stream"),
};

export const studentCreateFormSchema = z.object({
  ...baseShape,
  password: z
    .string()
    .refine((value) => value === "" || value.length >= 8, "Password must be at least 8 characters.")
    .refine((value) => value.length <= 255, "Password must be 255 characters or fewer."),
  courseIds: z.array(z.string()).min(1, "Select at least one course."),
});

export const studentEditFormSchema = z.object({
  ...baseShape,
  password: z.string(),
  courseIds: z.array(z.string()),
});

export type StudentFormValues = z.infer<typeof studentCreateFormSchema>;

export const remarkSchema = z.object({
  body: z.string().trim().min(1, "Write a remark.").max(2000, "Remarks must be 2000 characters or fewer."),
});

export const reasonSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(5, "Give a reason of at least 5 characters.")
    .max(500, "The reason must be 500 characters or fewer."),
});
