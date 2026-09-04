import { beforeEach, describe, expect, it } from "vitest";
import { clearDraft, loadDraft, saveDraft } from "./practiceDraft";

const store = () => window.localStorage;

describe("practice draft persistence", () => {
  beforeEach(() => store().clear());

  it("round-trips a draft per exercise id", () => {
    expect(loadDraft("sql-01")).toBeUndefined();
    saveDraft("sql-01", "SELECT 1");
    saveDraft("sql-02", "SELECT 2");
    expect(loadDraft("sql-01")).toBe("SELECT 1");
    expect(loadDraft("sql-02")).toBe("SELECT 2");
  });

  it("clears only the targeted exercise draft", () => {
    saveDraft("sql-01", "SELECT 1");
    saveDraft("sql-02", "SELECT 2");
    clearDraft("sql-01");
    expect(loadDraft("sql-01")).toBeUndefined();
    expect(loadDraft("sql-02")).toBe("SELECT 2");
  });

  it("overwrites an existing draft", () => {
    saveDraft("sql-01", "SELECT 1");
    saveDraft("sql-01", "SELECT 1 -- revised");
    expect(loadDraft("sql-01")).toBe("SELECT 1 -- revised");
  });
});
