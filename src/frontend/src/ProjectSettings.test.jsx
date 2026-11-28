/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import ProjectSettings from "./ProjectSettings";

// Radix Select uses pointer capture and ResizeObserver, which jsdom does not implement.
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

function project(id = 1) {
  return {
    id,
    name: "cisys",
    repositoryUrl: "https://github.com/org/repo",
    privateRepository: false,
    credentialRef: null,
    teamId: null,
    teamName: null,
  };
}

function renderSettings(api, target = project(), isAdmin = false) {
  return render(
    <ProjectSettings
      project={target}
      api={api}
      isAdmin={isAdmin}
      onSaved={() => {}}
      onCancel={() => {}}
    />,
  );
}

describe("ProjectSettings badge", () => {
  it("copies badge Markdown for the default branch of a public project", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    const api = vi.fn(async (path) =>
      path === "/project-pages/cisys/branches"
        ? { branches: ["feature", "main"] }
        : [],
    );
    render(
      <ProjectSettings
        project={project()}
        api={api}
        apiUrl="https://ci.example/api"
        isAdmin={false}
        onSaved={() => {}}
      />,
    );

    fireEvent.click(
      screen.getByRole("button", { name: "Copy badge Markdown" }),
    );
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(
        `[![build](https://ci.example/api/badge/cisys/main.svg)](${window.location.origin}/cisys/)`,
      ),
    );
  });

  it("has no badge button for private projects", () => {
    renderSettings(
      vi.fn(async () => []),
      {
        ...project(),
        privateRepository: true,
      },
    );
    expect(
      screen.queryByRole("button", { name: "Copy badge Markdown" }),
    ).toBeNull();
  });
});

describe("ProjectSettings", () => {
  it("shows the repository fields", () => {
    renderSettings(vi.fn(async () => []));
    expect(screen.getByLabelText("Project name").value).toBe("cisys");
    expect(screen.getByLabelText("Repository URL").value).toBe(
      "https://github.com/org/repo",
    );
  });

  it("saves the cache setting", async () => {
    const saved = { ...project(), cache: true };
    const api = vi.fn(async () => saved);
    render(
      <ProjectSettings
        project={project()}
        api={api}
        isAdmin={false}
        onSaved={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByLabelText("Cache").checked).toBe(false);
    fireEvent.click(screen.getByLabelText("Cache"));
    fireEvent.submit(document.getElementById("project-settings-form"));
    await vi.waitFor(() => expect(api).toHaveBeenCalled());
    const [, init] = api.mock.calls.find(([path]) => path === "/projects/1");
    expect(JSON.parse(init.body).cache).toBe(true);
    await vi.waitFor(() =>
      expect(screen.getByLabelText("Cache").checked).toBe(true),
    );
  });

  it("saves the project from the repository card only", async () => {
    const saved = { ...project(), name: "renamed" };
    const api = vi.fn(async () => saved);
    const onSaved = vi.fn();
    render(
      <ProjectSettings
        project={project()}
        api={api}
        isAdmin={false}
        onSaved={onSaved}
        onCancel={() => {}}
      />,
    );

    fireEvent.change(screen.getByLabelText("Project name"), {
      target: { value: "renamed" },
    });
    fireEvent.submit(document.getElementById("project-settings-form"));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    expect(api).toHaveBeenCalledWith(
      "/projects/1",
      expect.objectContaining({ method: "PUT" }),
    );
    expect(screen.queryByText("Automatic builds")).toBeNull();
  });

  it("does not let a lead make a private project public", () => {
    renderSettings(
      vi.fn(async () => []),
      {
        ...project(),
        privateRepository: true,
      },
    );
    expect(screen.getByLabelText("Private project").disabled).toBe(true);
  });

  it("lets a lead make a public project private", () => {
    renderSettings(vi.fn(async () => []));
    expect(screen.getByLabelText("Private project").disabled).toBe(false);
  });

  it("lets an admin make a private project public", () => {
    renderSettings(
      vi.fn(async () => []),
      { ...project(), privateRepository: true },
      true,
    );
    expect(screen.getByLabelText("Private project").disabled).toBe(false);
  });
});

describe("ProjectSettings webhooks", () => {
  function hooksApi(calls, hooks) {
    let list = hooks;
    return vi.fn(async (path, opts = {}) => {
      const method = opts.method || "GET";
      calls.push([method, path, opts.body ? JSON.parse(opts.body) : null]);
      if (path === "/projects/1/webhooks/deliveries") return [];
      if (path === "/projects/1/webhooks" && method === "GET") return list;
      if (path === "/projects/1/webhooks" && method === "POST") {
        const created = {
          id: 9,
          url: "https://new.example.com/…",
          onFinished: false,
          onFailed: true,
          onFixed: true,
          secret: "abc123",
        };
        list = [...list, { ...created, secret: undefined }];
        return created;
      }
      if (method === "DELETE") {
        list = list.filter((w) => !path.endsWith(`/${w.id}`));
        return null;
      }
      return {};
    });
  }

  const existing = {
    id: 3,
    url: "https://hooks.example.com/…",
    onFinished: false,
    onFailed: true,
    onFixed: false,
    lastDelivery: { status: "DELIVERED", responseStatus: 200 },
  };

  it("loads the webhooks when the tab is opened", async () => {
    const calls = [];
    renderSettings(hooksApi(calls, [existing]));
    expect(calls).toHaveLength(0);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Webhooks" }));
    const url = await screen.findByLabelText("Webhook URL");
    expect(url.placeholder).toBe("https://hooks.example.com/…");
    expect(screen.getByText("delivered")).toBeTruthy();
  });

  it("creates a webhook on Save and shows its secret once", async () => {
    const calls = [];
    renderSettings(hooksApi(calls, []));
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Webhooks" }));
    fireEvent.change(await screen.findByLabelText("URL"), {
      target: { value: "https://new.example.com/hook" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add webhook" }));
    fireEvent.submit(document.getElementById("project-settings-form"));
    await screen.findByText("abc123");
    const post = calls.find(
      ([m, p]) => m === "POST" && p === "/projects/1/webhooks",
    );
    expect(post[2]).toEqual({
      url: "https://new.example.com/hook",
      onFinished: false,
      onFailed: true,
      onFixed: true,
    });
  });

  it("deletes a removed webhook on Save", async () => {
    const calls = [];
    renderSettings(hooksApi(calls, [existing]));
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Webhooks" }));
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Remove https://hooks.example.com/…",
      }),
    );
    fireEvent.submit(document.getElementById("project-settings-form"));
    await vi.waitFor(() =>
      expect(
        calls.some(
          ([m, p]) => m === "DELETE" && p === "/projects/1/webhooks/3",
        ),
      ).toBe(true),
    );
  });
});

function renderUnified(
  api,
  target = project(),
  isAdmin = false,
  user = userFor(),
) {
  return render(
    <ProjectSettings
      project={target}
      api={api}
      isAdmin={isAdmin}
      user={user}
      onSaved={() => {}}
      onCancel={() => {}}
    />,
  );
}

function userFor() {
  return { username: "lead", subject: "lead-id", roles: ["lead"] };
}

function apiMock(calls, members, opts = {}) {
  const { postError, deleteError, putRepo, membersAfter } = opts;
  let gets = 0;
  return vi.fn(async (path, opts = {}) => {
    const method = opts.method || "GET";
    const bin = opts.body ? JSON.parse(opts.body) : null;
    calls.push([method, path, bin]);
    if (method === "GET" && path.startsWith("/users?")) {
      const users = ["lead", "member", "neo", "ghost"].map((username) => ({
        username,
      }));
      return { total: users.length, users };
    }
    if (method === "GET" && path === "/projects/1/team") {
      gets += 1;
      // After the initial load, honour the post-mutation list when provided.
      if (gets > 1 && membersAfter !== undefined) {
        if (typeof membersAfter === "function") return membersAfter();
        return membersAfter;
      }
      return members;
    }
    if (method === "PUT" && path === "/projects/1") {
      if (putRepo) throw putRepo;
      return {
        ...project(),
        name: bin?.name,
        repositoryUrl: bin?.repositoryUrl,
        privateRepository: bin?.privateRepository,
      };
    }
    if (method === "PUT" && path === "/projects/1/team-assignment")
      return {
        ...project(),
        teamId: bin.teamId,
        teamName: bin.teamId ? "build" : null,
      };
    if (method === "POST" && path === "/projects/1/team") {
      if (postError) throw postError;
      return { username: bin.username, role: bin.role };
    }
    if (method === "PUT")
      return { username: path.split("/").pop(), role: bin.role };
    if (method === "DELETE") {
      if (deleteError) throw deleteError;
      return null;
    }
    throw new Error("unexpected " + method + " " + path);
  });
}

const led = [
  { username: "lead", role: "LEAD" },
  { username: "member", role: "MEMBER" },
];

describe("ProjectSettings tabs (unified Save/Discard)", () => {
  it("does not fetch members until the Team tab is opened", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    expect(calls.filter(([, p]) => p === "/projects/1/team")).toHaveLength(0);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    expect(
      calls.filter(([m, p]) => p === "/projects/1/team" && m === "GET"),
    ).toHaveLength(1);
  });

  it("stages a member addition without POST, then saves on Save", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await screen.findByLabelText("User");
    await choose("User", "neo");
    fireEvent.click(screen.getByRole("button", { name: "Add member" }));
    expect(
      calls.some(([m, p]) => m === "POST" && p === "/projects/1/team"),
    ).toBe(false);
    expect(screen.getByRole("button", { name: "Save changes" }).disabled).toBe(
      false,
    );
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(
        calls.some(([m, p]) => m === "POST" && p === "/projects/1/team"),
      ).toBe(true),
    );
  });

  it("stages a role change without PUT, then saves on Save", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    await screen.findByLabelText("Role for member");
    await choose("Role for member", "lead");
    expect(
      calls.some(([m, p]) => m === "PUT" && p === "/projects/1/team/member"),
    ).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(screen.getByLabelText("Role for member").textContent).toContain(
        "lead",
      ),
    );
    expect(
      calls.some(
        ([m, p, b]) =>
          m === "PUT" && p === "/projects/1/team/member" && b?.role === "LEAD",
      ),
    ).toBe(true);
  });

  it("stages a removal without DELETE before Save", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    fireEvent.click(
      screen.getByRole("button", { name: "Remove member from the project" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Remove" }));
    expect(
      calls.some(([m, p]) => m === "DELETE" && p === "/projects/1/team/member"),
    ).toBe(false);
    expect(screen.queryByLabelText("Role for member")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));
    expect(screen.getByLabelText("Role for member")).toBeTruthy();
    vi.restoreAllMocks();
  });

  it("Discard restores the original team list", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await choose("Role for member", "lead");
    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));
    expect(screen.getByLabelText("Role for member").textContent).toContain(
      "member",
    );
    expect(calls.filter(([m]) => m === "PUT")).toHaveLength(0);
  });

  it("reports a partial failure and leaves the unapplied staged changes in place", async () => {
    const calls = [];
    const postError = Object.assign(new Error("Forbidden"), { status: 403 });
    const api = apiMock(calls, led, { postError });
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await screen.findByLabelText("User");
    await choose("User", "ghost");
    fireEvent.click(screen.getByRole("button", { name: "Add member" }));
    await choose("Role for member", "lead");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    // The POST fails first; the PUT has not been attempted yet.
    expect(
      (await screen.findAllByText(/failed while adding 'ghost'/)).length,
    ).toBeGreaterThan(0);
    expect(calls.some(([m]) => m === "PUT")).toBe(false);
    // The PUT would then be applied on a subsequent Save.
    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));
  });
});

const ledAfterSave = [
  { username: "lead", role: "LEAD" },
  { username: "member", role: "LEAD" },
];

describe("ProjectSettings tabs (Team save semantics)", () => {
  it("shows a success banner after all Team changes apply", async () => {
    const calls = [];
    const api = apiMock(calls, led, { membersAfter: ledAfterSave });
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await choose("Role for member", "lead");
    expect(screen.queryByText("Team saved.")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("Team saved.")).toBeTruthy();
  });

  it("does not claim success when a Team operation fails", async () => {
    const calls = [];
    const postError = Object.assign(new Error("Forbidden"), { status: 403 });
    const api = apiMock(calls, led, { postError });
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await screen.findByLabelText("User");
    await choose("User", "ghost");
    fireEvent.click(screen.getByRole("button", { name: "Add member" }));
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(calls.some(([m]) => m === "POST")).toBe(true),
    );
    expect(screen.queryByText("Team saved.")).toBeNull();
  });

  it("re-fetches members after a successful Team save", async () => {
    const calls = [];
    const api = apiMock(calls, led, { membersAfter: ledAfterSave });
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await choose("Role for member", "lead");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(screen.queryByText("Team saved.")).not.toBeNull(),
    );
    expect(
      calls.filter(([m, p]) => m === "GET" && p === "/projects/1/team").length,
    ).toBeGreaterThan(1);
  });

  it("shows an error when the refresh GET fails after successful mutations", async () => {
    const calls = [];
    const api = apiMock(calls, led, {
      membersAfter: () => {
        throw Object.assign(new Error("boom"), { status: 500 });
      },
    });
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await choose("Role for member", "lead");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() => expect(calls.some(([m]) => m === "PUT")).toBe(true));
    // The mutation happened, so the server already changed; the refresh failing must be visible.
    expect(await screen.findByText(/could not be refreshed/)).toBeTruthy();
    expect(screen.queryByText("Team saved.")).toBeNull();
  });

  it("does not re-fetch members when switching tabs and back", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    await screen.findAllByText(/member/i);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "General" }));
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    expect(
      calls.filter(([m, p]) => m === "GET" && p === "/projects/1/team").length,
    ).toBe(1);
  });

  it("keeps the staged list after switching tabs and back again", async () => {
    const calls = [];
    const api = apiMock(calls, led);
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    const _ = await screen.findAllByText(/member/i);
    expect(_.length).toBeGreaterThan(0);
    await choose("Role for member", "lead");
    fireEvent.mouseDown(screen.getByRole("tab", { name: "General" }));
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    expect(screen.getByLabelText("Role for member").textContent).toContain(
      "lead",
    );
    expect(screen.getByRole("button", { name: "Save changes" }).disabled).toBe(
      false,
    );
  });

  it("makes the fetched list the new baseline after a successful save", async () => {
    const calls = [];
    const api = apiMock(calls, led, { membersAfter: ledAfterSave });
    renderUnified(api);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Team" }));
    await screen.findAllByText(/member/i);
    await choose("Role for member", "lead");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(screen.queryByText("Team saved.")).not.toBeNull(),
    );
    // After save, the select must reflect the fresh baseline.
    expect(screen.getByLabelText("Role for member").textContent).toContain(
      "lead",
    );
    // And there is nothing to discard or save anymore; the buttons hide.
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
  });
});

describe("ProjectSettings team assignment", () => {
  it("does not render a project-level token editor", () => {
    renderUnified(apiMock([], led), { ...project(), privateRepository: true });
    expect(screen.queryByLabelText("Git token")).toBeNull();
    expect(screen.queryByText(/No token is set/)).toBeNull();
  });

  it("assigns a selected team through the team-assignment endpoint", async () => {
    const calls = [];
    render(
      <ProjectSettings
        project={project()}
        api={apiMock(calls, led)}
        teams={[{ id: 7, name: "build", role: "LEAD" }]}
        isAdmin={false}
        user={userFor()}
        onSaved={() => {}}
      />,
    );
    fireEvent.change(document.getElementById("settings-team"), {
      target: { value: "7" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/projects/1/team-assignment",
        { teamId: 7 },
      ]),
    );
  });

  it("clears the project team with a null assignment", async () => {
    const calls = [];
    render(
      <ProjectSettings
        project={{ ...project(), teamId: 7, teamName: "build" }}
        api={apiMock(calls, led)}
        teams={[{ id: 7, name: "build", role: "LEAD" }]}
        isAdmin={false}
        user={userFor()}
        onSaved={() => {}}
      />,
    );
    fireEvent.change(document.getElementById("settings-team"), {
      target: { value: "" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/projects/1/team-assignment",
        { teamId: null },
      ]),
    );
  });

  it("lets a LEAD make a private project public", async () => {
    const calls = [];
    renderUnified(apiMock(calls, led), {
      ...project(),
      privateRepository: true,
    });
    const checkbox = screen.getByLabelText("Private project");
    expect(checkbox.disabled).toBe(false);
    fireEvent.click(checkbox);
    expect(checkbox.checked).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/projects/1",
        expect.objectContaining({ privateRepository: false }),
      ]),
    );
  });

  it("leaves the private switch enabled for an admin", () => {
    renderUnified(
      apiMock([], led),
      { ...project(), privateRepository: true },
      true,
      { username: "admin", subject: "admin-id", roles: ["admin"] },
    );
    expect(screen.getByLabelText("Private project").disabled).toBe(false);
  });

  it("lets a LEAD make a public project private", () => {
    renderUnified(apiMock([], led), project());
    const checkbox = screen.getByLabelText("Private project");
    expect(checkbox.disabled).toBe(false);
    fireEvent.click(checkbox);
    expect(checkbox.checked).toBe(true);
    expect(document.getElementById("settings-team")).toBeTruthy();
  });
});

describe("unsaved-changes guard cooperation with ProjectSettings", () => {
  it("reports clean state after a successful General save (onDirtyChange called with false)", async () => {
    window.history.replaceState(null, "", "/cisys/settings");
    const api = vi.fn(async (path, opts = {}) => {
      if (path === "/projects/1" && opts.method === "PUT") {
        return { ...project(), name: JSON.parse(opts.body).name };
      }
      throw new Error("unexpected " + path);
    });
    const dirtyStates = [];
    const onDirtyChange = (v) => dirtyStates.push(v);
    render(
      <ProjectSettings
        project={project()}
        api={api}
        isAdmin={false}
        user={userFor()}
        onSaved={() => {}}
        onDirtyChange={onDirtyChange}
      />,
    );
    // Make a change so the form is dirty.
    fireEvent.change(screen.getByLabelText("Project name"), {
      target: { value: "renamed" },
    });
    expect(dirtyStates.at(-1)).toBe(true);
    fireEvent.submit(document.getElementById("project-settings-form"));
    await vi.waitFor(() => expect(dirtyStates.at(-1)).toBe(false));
  });

  it("does NOT show an empty warning fragment: the Save handler navigates cleanly with no dirty state popping up", async () => {
    window.history.replaceState(null, "", "/cisys/settings");
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const api = vi.fn(async (path, opts = {}) => {
      if (path === "/projects/1" && opts.method === "PUT") {
        return { ...project(), name: JSON.parse(opts.body).name };
      }
      throw new Error("unexpected " + path);
    });
    const onSaved = vi.fn();
    render(
      <ProjectSettings
        project={project()}
        api={api}
        isAdmin={false}
        user={userFor()}
        onSaved={onSaved}
        onDirtyChange={() => {}}
      />,
    );
    fireEvent.change(screen.getByLabelText("Project name"), {
      target: { value: "renamed" },
    });
    fireEvent.submit(document.getElementById("project-settings-form"));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(confirm).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it("commits saved repository fields when team assignment fails", async () => {
    const calls = [];
    const onSaved = vi.fn();
    let failTeam = true;
    const api = vi.fn(async (path, opts = {}) => {
      calls.push([
        opts.method || "GET",
        path,
        opts.body ? JSON.parse(opts.body) : null,
      ]);
      if (path === "/projects/1" && opts.method === "PUT") {
        return { ...project(), name: JSON.parse(opts.body).name };
      }
      if (path === "/projects/1/team-assignment" && opts.method === "PUT") {
        if (failTeam) throw new Error("Team assignment rejected");
        return { ...project(), name: "renamed", teamId: 7 };
      }
      throw new Error("unexpected " + path);
    });
    render(
      <ProjectSettings
        project={project()}
        api={api}
        teams={[{ id: 7, name: "build", role: "LEAD" }]}
        isAdmin={false}
        user={userFor()}
        onSaved={onSaved}
      />,
    );

    fireEvent.change(screen.getByLabelText("Project name"), {
      target: { value: "renamed" },
    });
    fireEvent.change(document.getElementById("settings-team"), {
      target: { value: "7" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(onSaved).toHaveBeenCalledWith(
      expect.objectContaining({ name: "renamed", teamId: null }),
    );
    expect(document.getElementById("settings-team").value).toBe("7");

    // Retrying Save only retries the failed team-assignment request.
    failTeam = false;
    calls.length = 0;
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await vi.waitFor(() =>
      expect(calls).toEqual([
        ["PUT", "/projects/1/team-assignment", { teamId: 7 }],
      ]),
    );
  });
});
