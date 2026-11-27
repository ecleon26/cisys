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
import { afterEach, describe, expect, it, vi } from "vitest";
import ProjectBuild from "./ProjectBuild";

vi.mock("./BuildLogView", () => ({ BuildLogPanel: () => null }));
vi.mock("./BuildComments", () => ({ default: () => null }));

afterEach(cleanup);

const build = {
  id: 42,
  number: 5,
  projectId: 7,
  status: "FAILED",
  branch: "main",
  revision: "0123456789abcdef0123456789abcdef01234567",
  trigger: "MANUAL",
  canRerun: true,
};

function renderBuild({ shown = build, rerun } = {}) {
  const load = vi.fn(async (path) => {
    if (path.endsWith("/rerun")) {
      if (rerun) return rerun();
      return { id: 43, branch: "main" };
    }
    return shown;
  });
  const onOpenBuild = vi.fn();
  render(
    <ProjectBuild
      name="demo"
      branch="main"
      buildId={42}
      load={load}
      onBack={() => {}}
      onOpenBuild={onOpenBuild}
      onNotFound={() => {}}
    />,
  );
  return { load, onOpenBuild };
}

describe("ProjectBuild re-run", () => {
  it("queues the build again and opens the new one", async () => {
    const { load, onOpenBuild } = renderBuild();
    fireEvent.click(await screen.findByLabelText("Re-run build"));
    await waitFor(() =>
      expect(onOpenBuild).toHaveBeenCalledWith("demo", "main", 43),
    );
    expect(load).toHaveBeenCalledWith("/projects/7/builds/42/rerun", {
      method: "POST",
    });
  });

  it("hides the button when the build cannot be run again", async () => {
    renderBuild({ shown: { ...build, canRerun: false } });
    expect(await screen.findByText("Build #5")).toBeTruthy();
    expect(screen.queryByLabelText("Re-run build")).toBeNull();
  });

  it("shows the server's reason when re-running fails", async () => {
    const { onOpenBuild } = renderBuild({
      rerun: () => {
        throw new Error("Build queue is full. Try again later.");
      },
    });
    fireEvent.click(await screen.findByLabelText("Re-run build"));
    expect(await screen.findByText(/queue is full/)).toBeTruthy();
    expect(onOpenBuild).not.toHaveBeenCalled();
  });

  it("ignores a stale response when switching to another build", async () => {
    let resolveFirst;
    const load = vi.fn((path) => {
      if (path.endsWith("/builds/42")) {
        return new Promise((resolve) => {
          resolveFirst = resolve;
        });
      }
      if (path.endsWith("/builds/43")) {
        return Promise.resolve({ ...build, id: 43, number: 6 });
      }
      return Promise.reject(new Error(`unexpected ${path}`));
    });
    const onNotFound = vi.fn();

    const { rerender } = render(
      <ProjectBuild
        name="demo"
        branch="main"
        buildId={42}
        load={load}
        onBack={() => {}}
        onOpenBuild={() => {}}
        onNotFound={onNotFound}
      />,
    );
    await waitFor(() =>
      expect(load).toHaveBeenCalledWith("/project-pages/demo/builds/42"),
    );

    rerender(
      <ProjectBuild
        name="demo"
        branch="main"
        buildId={43}
        load={load}
        onBack={() => {}}
        onOpenBuild={() => {}}
        onNotFound={onNotFound}
      />,
    );
    expect(await screen.findByText("Build #6")).toBeTruthy();

    resolveFirst({ ...build, id: 42, number: 5 });
    await Promise.resolve();
    expect(screen.getByText("Build #6")).toBeTruthy();
    expect(screen.queryByText("Build #5")).toBeNull();
    expect(onNotFound).not.toHaveBeenCalled();
  });
});
