import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import UsersPanel from "./UsersPanel";

// Radix Select and Popover use pointer capture and ResizeObserver, which jsdom
// does not implement.
beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

afterEach(cleanup);

async function choose(label, option) {
  const trigger = screen.getByLabelText(label);
  fireEvent.pointerDown(trigger, {
    button: 0,
    ctrlKey: false,
    pointerType: "mouse",
  });
  fireEvent.pointerUp(trigger, {
    button: 0,
    ctrlKey: false,
    pointerType: "mouse",
  });
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

const users = {
  total: 2,
  users: [
    {
      id: "a",
      username: "boss",
      role: "admin",
      enabled: true,
      manageable: false,
      projects: [],
    },
    {
      id: "m",
      username: "dev",
      role: "member",
      enabled: true,
      manageable: true,
      projects: [{ id: 1, name: "alpha" }],
    },
  ],
};

function api() {
  return vi.fn((path) =>
    Promise.resolve(
      path === "/users/projects" ? [{ id: 1, name: "alpha" }] : users,
    ),
  );
}

afterEach(cleanup);

describe("UsersPanel", () => {
  it("lets a lead add members only and manage only members", async () => {
    render(<UsersPanel api={api()} user={{ roles: ["lead"], subject: "l" }} />);
    expect(await screen.findByText("dev")).toBeTruthy();
    expect(screen.queryByLabelText("Role")).toBeNull();
    expect(screen.queryByLabelText("Edit boss")).toBeNull();
    expect(screen.getByLabelText("Edit dev")).toBeTruthy();
    expect(screen.queryByLabelText("Delete dev")).toBeNull();
  });

  it("lets an admin choose roles and delete others", async () => {
    const admin = { ...users.users[0], manageable: true };
    const load = vi.fn((path) =>
      Promise.resolve(
        path === "/users/projects"
          ? []
          : { total: 2, users: [admin, users.users[1]] },
      ),
    );
    render(<UsersPanel api={load} user={{ roles: ["admin"], subject: "a" }} />);
    expect(await screen.findByText("dev")).toBeTruthy();
    expect(screen.getAllByLabelText("Role").length).toBe(2);
    expect(screen.getByLabelText("Delete dev")).toBeTruthy();
    expect(screen.queryByLabelText("Delete boss")).toBeNull();
  });

  it("saves a project assignment chosen from the picker", async () => {
    const admin = { ...users.users[0], manageable: true };
    const calls = [];
    const load = vi.fn((path, options) => {
      calls.push({ path, options });
      return Promise.resolve(
        path === "/users/projects"
          ? [
              { id: 1, name: "alpha" },
              { id: 2, name: "beta" },
            ]
          : { total: 2, users: [admin, users.users[1]] },
      );
    });
    render(<UsersPanel api={load} user={{ roles: ["admin"], subject: "a" }} />);

    fireEvent.click(await screen.findByLabelText("Edit dev"));
    fireEvent.click(screen.getByLabelText("Projects for dev"));
    fireEvent.click(await screen.findByLabelText("beta"));
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await choose("Role for dev", "lead");
    fireEvent.click(screen.getByLabelText("Save dev"));

    await waitFor(() => {
      const put = calls.find(
        (call) => call.path === "/users/m" && call.options?.method === "PUT",
      );
      expect(put).toBeTruthy();
      expect(JSON.parse(put.options.body)).toEqual({
        id: "m",
        email: "",
        firstName: "",
        lastName: "",
        role: "lead",
        enabled: true,
        projectIds: [1, 2],
      });
    });
  });

  function makePaginatedApi(count) {
    let userList = Array.from({ length: count }, (_, idx) => ({
      id: `u${idx + 1}`,
      username: `user${idx + 1}`,
      role: "member",
      enabled: true,
      manageable: true,
      projects: [],
    }));
    return vi.fn((path, options) => {
      if (path === "/users/projects") return Promise.resolve([]);
      if (options?.method === "DELETE") {
        const id = path.slice("/users/".length);
        userList = userList.filter((u) => u.id !== id);
        return Promise.resolve();
      }
      const params = new URLSearchParams(path.split("?")[1] || "");
      const first = Number(params.get("first") || 0);
      const max = Number(params.get("max") || 50);
      return Promise.resolve({
        total: userList.length,
        users: userList.slice(first, first + max),
      });
    });
  }

  it("steps back to page 1 when the only user on page 2 is deleted", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    try {
      render(
        <UsersPanel
          api={makePaginatedApi(51)}
          user={{ roles: ["admin"], subject: "admin" }}
        />,
      );

      expect(await screen.findByText("user1")).toBeTruthy();
      fireEvent.click(screen.getByLabelText("Next page"));
      expect(await screen.findByText("user51")).toBeTruthy();
      expect(screen.getByText("51–51 of 51")).toBeTruthy();

      fireEvent.click(screen.getByLabelText("Delete user51"));

      await waitFor(() => expect(screen.getByText("user1")).toBeTruthy());
      expect(screen.queryByText("No users")).toBeNull();
    } finally {
      confirmSpy.mockRestore();
    }
  });

  it("steps back cleanly from page 3 when deleting the last user leaves 100", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    try {
      render(
        <UsersPanel
          api={makePaginatedApi(101)}
          user={{ roles: ["admin"], subject: "admin" }}
        />,
      );

      expect(await screen.findByText("user1")).toBeTruthy();
      fireEvent.click(screen.getByLabelText("Next page"));
      expect(await screen.findByText("user51")).toBeTruthy();
      fireEvent.click(screen.getByLabelText("Next page"));
      expect(await screen.findByText("user101")).toBeTruthy();
      expect(screen.getByText("101–101 of 101")).toBeTruthy();

      fireEvent.click(screen.getByLabelText("Delete user101"));

      await waitFor(() => expect(screen.getByText("user51")).toBeTruthy());
      expect(screen.getByText("51–100 of 100")).toBeTruthy();
      expect(screen.queryByText("101–100 of 100")).toBeNull();
      expect(screen.queryByText("No users")).toBeNull();
    } finally {
      confirmSpy.mockRestore();
    }
  });

  it("ignores a stale user list response that arrives after a newer filter", async () => {
    let resolveSlow;
    const slowFirst = new Promise((resolve) => {
      resolveSlow = resolve;
    });
    const load = vi.fn((path) => {
      if (path === "/users/projects") return Promise.resolve([]);
      if (path.includes("search=beta")) {
        return Promise.resolve({
          total: 1,
          users: [
            {
              id: "b",
              username: "beta-user",
              role: "member",
              enabled: true,
              manageable: true,
              projects: [],
            },
          ],
        });
      }
      return slowFirst;
    });

    render(
      <UsersPanel api={load} user={{ roles: ["admin"], subject: "admin" }} />,
    );
    await waitFor(() =>
      expect(
        load.mock.calls.filter(([p]) => p.startsWith("/users?")),
      ).toHaveLength(1),
    );

    fireEvent.change(screen.getByLabelText("Search users"), {
      target: { value: "beta" },
    });
    expect(await screen.findByText("beta-user")).toBeTruthy();

    resolveSlow({
      total: 1,
      users: [
        {
          id: "s",
          username: "stale-user",
          role: "member",
          enabled: true,
          manageable: true,
          projects: [],
        },
      ],
    });
    await Promise.resolve();
    expect(screen.getByText("beta-user")).toBeTruthy();
    expect(screen.queryByText("stale-user")).toBeNull();
  });
});
