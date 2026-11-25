import { apiClient } from "./client";
import { filenameFrom, saveDownload } from "@/lib/saveDownload";

export interface ImportSummary {
  profiles: number;
  dataSources: number;
  positions: number;
  fills: number;
  tags: number;
}

/** Downloads the current user's full backup as a JSON file. */
export async function downloadBackup(): Promise<void> {
  const res = await apiClient.get("/backup/export", { responseType: "blob" });
  saveDownload(
    new Blob([res.data], { type: "application/json" }),
    filenameFrom(res.headers["content-disposition"], "tradelog-export.json"),
  );
}

/** Thrown when the chosen file isn't valid JSON, before anything is sent to the server. */
export class InvalidBackupFileError extends Error {}

/** Restores a backup file with REPLACE semantics. Validates JSON locally first. */
export async function importBackup(file: File): Promise<ImportSummary> {
  let body: unknown;
  try {
    body = JSON.parse(await file.text());
  } catch {
    throw new InvalidBackupFileError();
  }
  const res = await apiClient.post<ImportSummary>("/backup/import?confirm=true", body);
  return res.data;
}
