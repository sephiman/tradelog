import { describe, expect, it } from "vitest";
import type { SyncRun } from "./sync";
import { summarizeSyncAll } from "./sync";

function run(over: Partial<SyncRun> = {}): SyncRun {
  return {
    id: "r1",
    dataSourceId: "d1",
    trigger: "MANUAL",
    status: "SUCCESS",
    startedAt: "2026-09-02T10:00:00Z",
    finishedAt: "2026-09-02T10:00:03Z",
    inserted: 0,
    updated: 0,
    errorCode: null,
    ...over,
  };
}

describe("summarizeSyncAll", () => {
  it("counts what landed when every source succeeded", () => {
    const s = summarizeSyncAll([run({ inserted: 3 }), run({ updated: 2 })], 2);

    expect(s).toEqual({ key: "sync.synced", params: { inserted: 3, updated: 2 }, tone: "success" });
  });

  it("names an outage rather than blaming the source", () => {
    const s = summarizeSyncAll([run({ status: "ERROR", errorCode: "SYNC_UNREACHABLE" })], 1);

    expect(s).toEqual({ key: "sync.unreachable", tone: "error" });
  });

  it("reports a failure as a failure, not as a sync of zero trades", () => {
    const s = summarizeSyncAll([run({ status: "ERROR", errorCode: "DATA_SOURCE_CREDENTIALS_INVALID" })], 1);

    expect(s).toEqual({ key: "sync.failed", tone: "error" });
  });

  it("says how many failed when only some did", () => {
    const runs = [run({ inserted: 4 }), run({ status: "ERROR", errorCode: "SYNC_UNREACHABLE" })];

    expect(summarizeSyncAll(runs, 2)).toEqual({
      key: "sync.partial",
      params: { inserted: 4, updated: 0, failed: 1 },
      tone: "error",
    });
  });

  // The reported bug: nothing came back, and the user was told a sync was already running.
  it("distinguishes nothing eligible from a sync already running", () => {
    expect(summarizeSyncAll([], 0)).toEqual({ key: "sync.nothingEnabled", tone: "info" });
    expect(summarizeSyncAll([], 2)).toEqual({ key: "sync.skipped", tone: "info" });
  });
});
