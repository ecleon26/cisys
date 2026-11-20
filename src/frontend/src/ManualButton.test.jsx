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
import ManualButton from "./ManualButton";

afterEach(cleanup);

describe("ManualButton", () => {
  it("opens the manual", () => {
    const onOpen = vi.fn();
    render(<ManualButton active={false} onOpen={onOpen} />);
    const link = screen.getByRole("link", { name: "Manual" });
    expect(link.getAttribute("href")).toBe("/manual");
    expect(link.getAttribute("aria-current")).toBeNull();
    fireEvent.click(link);
    expect(onOpen).toHaveBeenCalled();
  });

  it("marks the current page", () => {
    render(<ManualButton active onOpen={() => {}} />);
    expect(
      screen.getByRole("link", { name: "Manual" }).getAttribute("aria-current"),
    ).toBe("page");
  });
});
