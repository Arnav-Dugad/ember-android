import assert from "node:assert/strict";
import { test } from "node:test";
import { ageOn } from "./ageGate.js";

const on = (y: number, m: number, d: number) => new Date(Date.UTC(y, m - 1, d));

test("turns 18 exactly on the birthday", () => {
  assert.equal(ageOn("2008-10-27", on(2026, 10, 27)), 18);
  assert.equal(ageOn("2008-10-28", on(2026, 10, 27)), 17);
});

test("rejects impossible, malformed and future dates", () => {
  assert.equal(ageOn("2001-02-30", on(2026, 1, 1)), null);
  assert.equal(ageOn("2001-13-01", on(2026, 1, 1)), null);
  assert.equal(ageOn("01/02/2001", on(2026, 1, 1)), null);
  assert.equal(ageOn("2030-01-01", on(2026, 1, 1)), null);
});

test("leap-day birthdays age on 1 March in common years", () => {
  assert.equal(ageOn("2008-02-29", on(2026, 2, 28)), 17);
  assert.equal(ageOn("2008-02-29", on(2026, 3, 1)), 18);
});
