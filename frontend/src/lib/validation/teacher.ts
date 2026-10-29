import { z } from "zod";

const profileFields = {
  firstName: z.string().trim().min(1, "First name is required.").max(120),
  lastName: z.string().trim().min(1, "Last name is required.").max(120),
  contactNumber: z.string().trim().min(1, "Contact number is required.").max(40),
  instituteCommissionPercent: z.number().min(0, "Commission must be 0 or higher.").max(100, "Commission must be 100 or lower."),
  qualifications: z.string().max(2000).optional(),
  description: z.string().max(5000).optional(),
  profileMediaFileId: z.string().optional(),
  publicProfileDisabled: z.boolean(),
};

export const teacherCreateSchema = z.object({
  name: z.string().trim().min(1).max(255),
  firstName: profileFields.firstName,
  lastName: profileFields.lastName,
  contactNumber: profileFields.contactNumber,
  instituteCommissionPercent: z.number().min(0, "Commission must be 0 or higher.").max(100, "Commission must be 100 or lower."),
  qualifications: profileFields.qualifications,
  description: profileFields.description,
  profileMediaFileId: profileFields.profileMediaFileId,
  publicProfileDisabled: profileFields.publicProfileDisabled,
  email: z.string().trim().email("Enter a valid email address.").max(255),
  password: z.string().min(8, "Password must be at least 8 characters.").max(255),
});

export const teacherUpdateSchema = z.object(profileFields);

export type TeacherCreateFormValues = z.infer<typeof teacherCreateSchema>;
export type TeacherUpdateFormValues = z.infer<typeof teacherUpdateSchema>;
