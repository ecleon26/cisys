/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ProjectBuilds from "./ProjectBuilds";

afterEach(cleanup);

function commitsFor(branch) {
  return {
    commits: [
      {
        revision: branch.repeat(40),
        message: `${branch} commit`,
        author: "Ada",
        authoredAt: "2026-01-02T03:04:05Z",
        builds: [],
      },
    ],
    hasMore: false,
    reason: null,
  };
}

function projectLoad() {
  return vi.fn(async (path) => {
    if (path === "/project-pages/demo/branches")
      return { branches: ["main", "dev"] };
    if (path.includes("/commits?")) {
      const url = new URL(path, "http://localhost");
      return commitsFor(url.searchParams.get("branch"));
    }
    return {
      project: {
        id: 1,
        name: "demo",
        repositoryUrl: "https://example.test/demo.git",
        privateRepository: false,
      },
      builds: [],
    };
  });
}

function Harness({ load, onOpenBuild = () => {} }) {
  const [tab, setTab] = useState("builds");
  return (
    <ProjectBuilds
      name="demo"
      load={load}
      onOpenBuild={onOpenBuild}
      onNotFound={() => {}}
      tab={tab}
      onTabChange={setTab}
    />
  );
}

const commitCalls = (load) =>
  load.mock.calls.filter(([path]) => path.includes("/commits?"));
const FIND = { timeout: 5000 };

describe("ProjectBuilds badge", () => {
  it("copies badge Markdown for the selected branch of a public project", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    render(
      <ProjectBuilds
        name="demo"
        load={projectLoad()}
        apiUrl="https://ci.example/api"
        onNotFound={() => {}}
      />,
    );

    fireEvent.click(
      await screen.findByRole("button", { name: "Copy badge Markdown" }, FIND),
    );
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(
        `[![build](https://ci.example/api/badge/demo/main.svg)](${window.location.origin}/demo/)`,
      ),
    );
    expect(await screen.findByText("Copied", FIND)).toBeTruthy();
  });

  it("has no badge button for private projects", async () => {
    const base = projectLoad();
    const load = vi.fn(async (path) => {
      const data = await base(path);
      return data.project
        ? { ...data, project: { ...data.project, privateRepository: true } }
        : data;
    });
    render(<Harness load={load} />);

    expect(await screen.findByText("No builds", FIND)).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: "Copy badge Markdown" }),
    ).toBeNull();
  });
});

describe("ProjectBuilds tabs", () => {
  it("does not fetch commits until the Commits tab is opened", async () => {
    const load = projectLoad();
    render(<Harness load={load} />);

    expect(await screen.findByText("No builds", FIND)).toBeTruthy();
    expect(commitCalls(load)).toHaveLength(0);

    fireEvent.mouseDown(screen.getByRole("tab", { name: "Commits" }));
    expect(await screen.findByText("main commit", FIND)).toBeTruthy();
    expect(commitCalls(load)).toHaveLength(1);
    expect(screen.getByRole("tab", { name: "Commits" }).dataset.state).toBe(
      "active",
    );
  });

  it("switches between the Builds and Commits tabs", async () => {
    const load = projectLoad();
    render(<Harness load={load} />);

    expect(await screen.findByText("No builds", FIND)).toBeTruthy();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Commits" }));
    expect(await screen.findByText("main commit", FIND)).toBeTruthy();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Builds" }));
    expect(await screen.findByText("No builds", FIND)).toBeTruthy();
    expect(screen.getByRole("tab", { name: "Builds" }).dataset.state).toBe(
      "active",
    );
  });

  it("keeps the Commits tab when the branch changes and reloads its commits", async () => {
    const load = projectLoad();
    render(<Harness load={load} />);

    expect(await screen.findByText("No builds", FIND)).toBeTruthy();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Commits" }));
    expect(await screen.findByText("main commit", FIND)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "dev" }));

    expect(await screen.findByText("dev commit", FIND)).toBeTruthy();
    expect(screen.queryByText("main commit")).toBeNull();
    expect(screen.getByRole("tab", { name: "Commits" }).dataset.state).toBe(
      "active",
    );
    await waitFor(() =>
      expect(
        commitCalls(load).some(([path]) => path.includes("branch=dev")),
      ).toBe(true),
    );
  });
});
