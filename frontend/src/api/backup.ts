import { apiClient } from "./client";

export interface ImportSummary {
  profiles: number;
  dataSources: number;
  positions: number;
  fills: number;
  tags: number;
}

/** Reads the Content-Disposition filename, falling back to a sensible default. */
function filenameFrom(header: unknown): string {
  if (typeof header === "string") {
    const match = /filename="?([^"]+)"?/.exec(header);
    if (match) return match[1];
  }
  return "tradelog-export.json";
}

/** Downloads the current user's full backup as a JSON file via a transient object URL. */
export async function downloadBackup(): Promise<void> {
  const res = await apiClient.get("/backup/export", { responseType: "blob" });
  const url = URL.createObjectURL(new Blob([res.data], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filenameFrom(res.headers["content-disposition"]);
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
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
