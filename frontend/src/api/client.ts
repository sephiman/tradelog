import axios, { type AxiosError } from "axios";
import i18n from "@/i18n";

function readCookie(name: string): string | null {
  return (
    document.cookie
      .split("; ")
      .map((c) => c.split("="))
      .find(([k]) => k === name)?.[1] ?? null
  );
}

export const apiClient = axios.create({
  baseURL: "/api",
  withCredentials: true,
  headers: { "Content-Type": "application/json" },
});

apiClient.interceptors.request.use((config) => {
  const token = readCookie("XSRF-TOKEN");
  if (token && config.method && ["post", "put", "patch", "delete"].includes(config.method.toLowerCase())) {
    config.headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
  }
  return config;
});

export interface ApiError {
  code: string;
  message: string;
  fields?: Record<string, string>;
}

export function asApiError(err: unknown): ApiError {
  const ax = err as AxiosError<ApiError>;
  if (ax?.response?.data?.code) return ax.response.data;
  // No server payload (network failure, backend down) — show a localized message rather than
  // axios's raw English "Network Error".
  return { code: "UNKNOWN", message: i18n.t("common.networkError") };
}

export async function seedCsrf(): Promise<void> {
  await apiClient.get("/auth/csrf");
}
