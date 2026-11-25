/** Reads the Content-Disposition filename, falling back to [fallback]. */
export function filenameFrom(header: unknown, fallback: string): string {
  if (typeof header === "string") {
    const match = /filename="?([^"]+)"?/.exec(header);
    if (match) return match[1];
  }
  return fallback;
}

/** Saves [blob] as a file through a transient object URL. */
export function saveDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
