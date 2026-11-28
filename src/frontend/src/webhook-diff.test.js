/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import { body, diffWebhooks, newRow, toDraft } from "./webhook-diff";

const server = [
  {
    id: 1,
    url: "https://hooks.example.com/…",
    onFinished: false,
    onFailed: true,
    onFixed: true,
  },
  {
    id: 2,
    url: "https://chat.example.com/…",
    onFinished: true,
    onFailed: false,
    onFixed: false,
  },
];

describe("webhook diff", () => {
  it("is unchanged for an untouched draft", () => {
    expect(diffWebhooks(server, toDraft(server)).changed).toBe(false);
  });

  it("finds creates, event changes, URL changes and deletes", () => {
    const draft = toDraft(server);
    draft[0].onFinished = true;
    draft.splice(1, 1);
    draft.push(newRow("https://new.example.com/hook", 1));
    const plan = diffWebhooks(server, draft);
    expect(plan.creates.map((r) => r.url)).toEqual([
      "https://new.example.com/hook",
    ]);
    expect(plan.updates.map((r) => r.id)).toEqual([1]);
    expect(plan.deletes.map((w) => w.id)).toEqual([2]);
  });

  it("treats a typed URL as an update", () => {
    const draft = toDraft(server);
    draft[1].url = "https://chat.example.com/other";
    expect(diffWebhooks(server, draft).updates.map((r) => r.id)).toEqual([2]);
  });

  it("sends null to keep the stored URL", () => {
    const [row] = toDraft(server);
    expect(JSON.parse(body(row))).toEqual({
      url: null,
      onFinished: false,
      onFailed: true,
      onFixed: true,
    });
  });
});
