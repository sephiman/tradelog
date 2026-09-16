import { useQuery } from "@tanstack/react-query";
import { apiClient, seedCsrf } from "./client";

/** What the app may offer before anyone is signed in. Both flags follow the optional SMTP group:
 *  they report false until the operator configures it, and the UI shows nothing for them. */
export interface AuthFeatures {
  passwordReset: boolean;
  emailChangeVerified: boolean;
}

export function useAuthFeatures() {
  return useQuery({
    queryKey: ["auth-features"],
    queryFn: async () => (await apiClient.get<AuthFeatures>("/auth/features")).data,
    staleTime: Infinity,
  });
}

export async function requestPasswordReset(email: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/password-reset", { email });
}

export async function validatePasswordResetToken(token: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/password-reset/validate", { token });
}

export async function confirmPasswordReset(token: string, newPassword: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/password-reset/confirm", { token, newPassword });
}

export interface EmailChangeResult {
  /** `pending` when a confirmation link was mailed, `applied` when the address changed right away. */
  status: "pending" | "applied";
  email: string;
}

export async function requestEmailChange(newEmail: string, currentPassword: string): Promise<EmailChangeResult> {
  const res = await apiClient.post<EmailChangeResult>("/auth/email", { newEmail, currentPassword });
  return res.data;
}

export async function confirmEmailChange(token: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/email/confirm", { token });
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await apiClient.post("/auth/password", { currentPassword, newPassword });
}
