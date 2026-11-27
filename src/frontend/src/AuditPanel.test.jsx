import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import AuditPanel from "./AuditPanel";
import { locale } from "./i18n";

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
  window.scrollTo = () => {};
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

afterEach(cleanup);

const event = {
  id: 7,
  occurredAt: "2026-01-02T03:04:05Z",
  action: "project.create",
  outcome: "SUCCESS",
  actorSubject: "admin-id",
  actorUsername: "admin",
  actorRoles: ["admin"],
  projectId: 3,
  projectName: "alpha",
  targetType: "PROJECT",
  targetId: "3",
  targetName: "alpha",
  summary: "Created project alpha",
  details: { name: "alpha" },
  requestId: "request-1",
  httpMethod: "POST",
  path: "/api/projects",
  statusCode: null,
};

const otherEvent = {
  ...event,
  id: 8,
  action: "project.update",
  summary: "Updated project beta",
  projectName: "beta",
  targetName: "beta",
};

function listCalls(load) {
  return load.mock.calls.filter(([path]) => String(path).startsWith("/audit?"));
}

function lastQuery(load) {
  return new URLSearchParams(listCalls(load).at(-1)[0].split("?")[1]);
}

async function choose(label, option) {
  const trigger = screen.getByLabelText(label);
  fireEvent.pointerDown(trigger, { button: 0, pointerType: "mouse" });
  fireEvent.pointerUp(trigger, { button: 0, pointerType: "mouse" });
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

describe("AuditPanel", () => {
  it("expands an event's details inline directly under its row", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 1, events: [event] }
          : { ...event, details: { name: "alpha", nested: true } },
      ),
    );
    const scrollTo = vi.spyOn(window, "scrollTo");
    render(<AuditPanel api={load} />);

    expect(await screen.findByText("Created project alpha")).toBeTruthy();
    expect(screen.getByText("project.create")).toBeTruthy();
    expect(screen.getByText(/by admin/)).toBeTruthy();
    const row = screen
      .getByText("Created project alpha")
      .closest(".audit-event");
    expect(row).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    expect(await screen.findByText(/"nested": true/)).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(load).toHaveBeenCalledWith("/audit/7");
    expect(screen.getByLabelText("Hide details for event 7")).toBeTruthy();

    expect(within(row).getByText(/"nested": true/)).toBeTruthy();
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("shows an explicit timezone in the list and the details", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 1, events: [event] }
          : { ...event, details: { nested: true } },
      ),
    );
    render(<AuditPanel api={load} />);

    const expected = new Date(event.occurredAt).toLocaleString(locale(), {
      timeZoneName: "short",
    });
    expect(expected).not.toBe(
      new Date(event.occurredAt).toLocaleString(locale()),
    );
    const row = (await screen.findByText("Created project alpha")).closest(
      ".audit-event",
    );
    expect(within(row).getByText(expected)).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    await screen.findByText(/"nested": true/);
    const detail = document.querySelector(".audit-inline-detail");
    expect(within(detail).getByText(expected)).toBeTruthy();
  });

  it("collapses the details when the same control is clicked again", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 1, events: [event] }
          : { ...event, details: { nested: true } },
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    expect(await screen.findByText(/"nested": true/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Hide details for event 7"));
    await waitFor(() => {
      expect(screen.queryByText(/"nested": true/)).toBeNull();
    });
    expect(screen.getByLabelText("Show details for event 7")).toBeTruthy();
  });

  it("closes the details with the X control", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 1, events: [event] }
          : { ...event, details: { nested: true } },
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    expect(await screen.findByText(/"nested": true/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Close details for event 7"));
    await waitFor(() => {
      expect(screen.queryByText(/"nested": true/)).toBeNull();
    });
  });

  it("expands only one event at a time", async () => {
    const load = vi.fn((path) => {
      if (String(path).startsWith("/audit?")) {
        return Promise.resolve({ total: 2, events: [event, otherEvent] });
      }
      return Promise.resolve(
        path === "/audit/7"
          ? { ...event, details: { first: true } }
          : { ...otherEvent, details: { second: true } },
      );
    });
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    expect(await screen.findByText(/"first": true/)).toBeTruthy();
    expect(document.querySelectorAll(".audit-inline-detail")).toHaveLength(1);

    fireEvent.click(screen.getByLabelText("Show details for event 8"));
    expect(await screen.findByText(/"second": true/)).toBeTruthy();
    expect(screen.queryByText(/"first": true/)).toBeNull();
    expect(document.querySelectorAll(".audit-inline-detail")).toHaveLength(1);
    expect(screen.getByLabelText("Show details for event 7")).toBeTruthy();
  });

  it("keeps filters and the current page when details collapse", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 60, events: [event] }
          : { ...event, details: { nested: true } },
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.change(screen.getByLabelText("Search the audit log"), {
      target: { value: "alpha" },
    });
    await waitFor(() => {
      expect(lastQuery(load).get("search")).toBe("alpha");
    });

    fireEvent.click(screen.getByLabelText("Next page"));
    await waitFor(() => {
      expect(lastQuery(load).get("first")).toBe("50");
    });

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    expect(await screen.findByText(/"nested": true/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Close details for event 7"));
    await waitFor(() => {
      expect(screen.queryByText(/"nested": true/)).toBeNull();
    });

    expect(screen.getByLabelText("Search the audit log").value).toBe("alpha");
    const query = lastQuery(load);
    expect(query.get("search")).toBe("alpha");
    expect(query.get("first")).toBe("50");
    expect(query.get("max")).toBe("50");
    expect(screen.getByText("Page 2 of 2")).toBeTruthy();
  });

  it("shows pagination controls and pages through results", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 260, events: [event] }
          : event,
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    expect(screen.getByText("260 events")).toBeTruthy();
    expect(screen.getByText("Page 1 of 6")).toBeTruthy();
    expect(screen.getByText("1–50 of 260")).toBeTruthy();
    expect(screen.getByLabelText("Previous page").disabled).toBe(true);
    expect(screen.getByLabelText("Next page").disabled).toBe(false);

    fireEvent.click(screen.getByLabelText("Next page"));
    await waitFor(() => {
      expect(lastQuery(load).get("first")).toBe("50");
    });
    expect(screen.getByText("Page 2 of 6")).toBeTruthy();
    expect(screen.getByText("51–100 of 260")).toBeTruthy();
    expect(screen.getByLabelText("Previous page").disabled).toBe(false);

    fireEvent.click(screen.getByLabelText("Previous page"));
    await waitFor(() => {
      expect(lastQuery(load).get("first")).toBe("0");
    });
    expect(screen.getByText("Page 1 of 6")).toBeTruthy();
  });

  it("requests exact offsets with a page size of 25", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 80, events: [event] }
          : event,
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    await choose("Page size", "25");
    await waitFor(() => {
      expect(lastQuery(load).get("max")).toBe("25");
      expect(lastQuery(load).get("first")).toBe("0");
    });

    fireEvent.click(screen.getByLabelText("Next page"));
    await waitFor(() => {
      expect(lastQuery(load).get("first")).toBe("25");
      expect(lastQuery(load).get("max")).toBe("25");
    });

    fireEvent.click(screen.getByLabelText("Previous page"));
    await waitFor(() => {
      expect(lastQuery(load).get("first")).toBe("0");
      expect(lastQuery(load).get("max")).toBe("25");
    });

    await choose("Page size", "50");
    await waitFor(() => {
      expect(lastQuery(load).get("first")).toBe("0");
      expect(lastQuery(load).get("max")).toBe("50");
    });
  });

  it("changes the page size and returns to the first page", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 260, events: [event] }
          : event,
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    await choose("Page size", "100");
    await waitFor(() => {
      expect(lastQuery(load).get("max")).toBe("100");
      expect(lastQuery(load).get("first")).toBe("0");
    });
    expect(screen.getByText("Page 1 of 3")).toBeTruthy();
    expect(screen.getByText("1–100 of 260")).toBeTruthy();

    await choose("Page size", "25");
    await waitFor(() => {
      expect(lastQuery(load).get("max")).toBe("25");
    });
    expect(screen.getByText("Page 1 of 11")).toBeTruthy();
    expect(screen.getByText("1–25 of 260")).toBeTruthy();
  });

  it("sends the chosen filters and resets to the first page", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        String(path).startsWith("/audit?")
          ? { total: 1, events: [event] }
          : event,
      ),
    );
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.change(screen.getByLabelText("Search the audit log"), {
      target: { value: "alpha" },
    });
    fireEvent.change(screen.getByLabelText("Action"), {
      target: { value: "project.create" },
    });
    fireEvent.change(screen.getByLabelText("From"), {
      target: { value: "2026-01-01T00:00" },
    });

    await waitFor(() => {
      const query = lastQuery(load);
      expect(query.get("search")).toBe("alpha");
      expect(query.get("action")).toBe("project.create");
      expect(query.get("from")).toBe(
        new Date("2026-01-01T00:00").toISOString(),
      );
      expect(query.get("first")).toBe("0");
    });
  });

  it("does not send a one-character search", async () => {
    const load = vi.fn(() => Promise.resolve({ total: 1, events: [event] }));
    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.change(screen.getByLabelText("Search the audit log"), {
      target: { value: "a" },
    });
    await waitFor(() => expect(listCalls(load)).toHaveLength(2));
    expect(lastQuery(load).get("search")).toBeNull();
  });

  it("clears a stale error after a successful load", async () => {
    const load = vi
      .fn()
      .mockRejectedValueOnce(new Error("Nope"))
      .mockResolvedValueOnce({ total: 1, events: [event] });
    render(<AuditPanel api={load} />);
    expect((await screen.findByRole("alert")).textContent).toContain("Nope");

    fireEvent.change(screen.getByLabelText("Search the audit log"), {
      target: { value: "alpha" },
    });
    await screen.findByText("Created project alpha");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows the empty state and surfaces errors", async () => {
    const empty = vi.fn(() => Promise.resolve({ total: 0, events: [] }));
    const { unmount } = render(<AuditPanel api={empty} />);
    expect(await screen.findByText("No audit events")).toBeTruthy();
    unmount();

    const failing = vi.fn(() => Promise.reject(new Error("Nope")));
    render(<AuditPanel api={failing} />);
    expect((await screen.findByRole("alert")).textContent).toContain("Nope");
  });

  it("ignores a stale audit list response that arrives after a newer filter", async () => {
    let resolveSlow;
    const slowFirst = new Promise((resolve) => {
      resolveSlow = resolve;
    });
    const load = vi.fn((path) => {
      if (path.includes("search=beta")) {
        return Promise.resolve({
          total: 1,
          events: [{ ...event, id: 9, summary: "Filtered beta event" }],
        });
      }
      return slowFirst;
    });

    render(<AuditPanel api={load} />);
    await waitFor(() => expect(listCalls(load)).toHaveLength(1));

    fireEvent.change(screen.getByLabelText("Search the audit log"), {
      target: { value: "beta" },
    });
    expect(await screen.findByText("Filtered beta event")).toBeTruthy();

    resolveSlow({
      total: 1,
      events: [{ ...event, id: 1, summary: "Stale initial event" }],
    });
    await Promise.resolve();
    expect(screen.getByText("Filtered beta event")).toBeTruthy();
    expect(screen.queryByText("Stale initial event")).toBeNull();
  });

  it("ignores a stale detail response when another event is opened", async () => {
    let resolveFirstDetail;
    const load = vi.fn((path) => {
      if (path === "/audit/7") {
        return new Promise((resolve) => {
          resolveFirstDetail = resolve;
        });
      }
      if (path === "/audit/8") {
        return Promise.resolve({ ...otherEvent, details: { which: "second" } });
      }
      return Promise.resolve({ total: 2, events: [event, otherEvent] });
    });

    render(<AuditPanel api={load} />);
    await screen.findByText("Created project alpha");

    fireEvent.click(screen.getByLabelText("Show details for event 7"));
    fireEvent.click(screen.getByLabelText("Show details for event 8"));
    expect(await screen.findByText(/"which": "second"/)).toBeTruthy();

    resolveFirstDetail({ ...event, details: { which: "first" } });
    await Promise.resolve();
    expect(screen.getByText(/"which": "second"/)).toBeTruthy();
    expect(screen.queryByText(/"which": "first"/)).toBeNull();
  });
});
