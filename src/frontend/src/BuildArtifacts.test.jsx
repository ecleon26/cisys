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
import BuildArtifacts, { formatSize } from "./BuildArtifacts";

afterEach(cleanup);

const LIST = {
  truncated: true,
  files: [
    {
      id: 7,
      name: "Jar",
      path: "target/app.jar",
      size: 2048,
      sha256: "a".repeat(64),
    },
  ],
};

describe("BuildArtifacts", () => {
  it("shows nothing when the build has no artifacts", async () => {
    const load = vi.fn().mockResolvedValue({ truncated: false, files: [] });
    const { container } = render(
      <BuildArtifacts name="p" buildId={1} load={load} />,
    );
    await waitFor(() =>
      expect(load).toHaveBeenCalledWith("/project-pages/p/builds/1/artifacts"),
    );
    expect(container.innerHTML).toBe("");
  });

  it("shows nothing to visitors who are not signed in", async () => {
    const load = vi
      .fn()
      .mockRejectedValue(
        Object.assign(new Error("Request failed (401)"), { status: 401 }),
      );
    const { container } = render(
      <BuildArtifacts name="p" buildId={1} load={load} />,
    );
    await waitFor(() => expect(load).toHaveBeenCalled());
    expect(container.innerHTML).toBe("");
  });

  it("lists the files and downloads one", async () => {
    const blob = new Blob(["jar"]);
    const response = {
      headers: {
        get: () => 'attachment; filename="app.jar"',
      },
      blob: () => Promise.resolve(blob),
    };
    const load = vi.fn((path) =>
      Promise.resolve(path.endsWith("/artifacts") ? LIST : response),
    );
    URL.createObjectURL = vi.fn(() => "blob:x");
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});

    render(<BuildArtifacts name="p" buildId={1} load={load} />);
    expect(await screen.findByText("target/app.jar")).toBeTruthy();
    expect(screen.getByText("2.0 KiB")).toBeTruthy();
    expect(
      screen.getByText("The artifacts were cut at their limit."),
    ).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Download target/app.jar"));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(load).toHaveBeenCalledWith(
      "/project-pages/p/builds/1/artifacts/7",
      expect.objectContaining({ raw: true }),
    );

    fireEvent.click(screen.getByLabelText("Download all"));
    await waitFor(() =>
      expect(load).toHaveBeenCalledWith(
        "/project-pages/p/builds/1/artifacts.zip",
        expect.objectContaining({ raw: true }),
      ),
    );
    click.mockRestore();
  });

  it("formats sizes", () => {
    expect(formatSize(10)).toBe("10 B");
    expect(formatSize(1536)).toBe("1.5 KiB");
    expect(formatSize(3 * 1024 * 1024)).toBe("3.0 MiB");
  });
});
