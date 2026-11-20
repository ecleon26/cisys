/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import ManualPage from "./ManualPage";

const chapters = [
  {
    slug: "roles",
    title: "Roles",
    group: "User guide",
    body: "# Roles\n\n## Access summary\n\n| Action | Admin |\n| --- | --- |\n| Delete | Yes |",
  },
  {
    slug: "builds",
    title: "Builds",
    group: "User guide",
    body: "# Builds\n\nSee [cisys](https://github.com/mnemosyne-systems/cisys).",
  },
];

afterEach(cleanup);

describe("ManualPage", () => {
  it("renders the chapter with tables and heading anchors", () => {
    const { container } = render(
      <ManualPage slug="roles" onOpen={() => {}} chapters={chapters} />,
    );
    expect(
      screen.getByRole("heading", { level: 1, name: "Roles" }),
    ).toBeTruthy();
    expect(container.querySelector("#access-summary")).toBeTruthy();
    expect(screen.getByRole("cell", { name: "Delete" })).toBeTruthy();
  });

  it("opens other chapters from the list and the pager", () => {
    const onOpen = vi.fn();
    render(<ManualPage slug="roles" onOpen={onOpen} chapters={chapters} />);
    const nav = screen.getByRole("navigation", { name: "Manual" });
    expect(nav.querySelector('[aria-current="page"]').textContent).toBe(
      "Roles",
    );
    const next = document.querySelector(".manual-next");
    expect(next.getAttribute("href")).toBe("/manual/builds");
    fireEvent.click(next);
    expect(onOpen).toHaveBeenCalledWith("builds");
  });

  it("opens external links in a new tab", () => {
    render(<ManualPage slug="builds" onOpen={() => {}} chapters={chapters} />);
    expect(screen.getByRole("link", { name: "cisys" }).target).toBe("_blank");
    expect(document.querySelector(".manual-prev")).toBeTruthy();
    expect(document.querySelector(".manual-next")).toBeNull();
  });
});
