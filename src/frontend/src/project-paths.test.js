/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import {
  manualFromPath,
  manualPath,
  projectBuildFromPath,
  projectNameFromPath,
  projectSettingsFromPath,
} from "./project-paths";

describe("project-paths", () => {
  it("reads the manual paths", () => {
    expect(manualFromPath("/manual")).toEqual({ slug: null });
    expect(manualFromPath("/manual/")).toEqual({ slug: null });
    expect(manualFromPath("/manual/builds")).toEqual({ slug: "builds" });
    expect(manualFromPath("/cisys/")).toBeNull();
    expect(manualFromPath("/manual/a/b")).toBeNull();
    expect(manualPath("builds")).toBe("/manual/builds");
  });

  it("never reads the manual as a project", () => {
    expect(projectNameFromPath("/manual")).toBeNull();
    expect(projectNameFromPath("/Manual/")).toBeNull();
    expect(projectSettingsFromPath("/manual/settings")).toBeNull();
    expect(projectBuildFromPath("/manual/main/build/1")).toBeNull();
    expect(projectNameFromPath("/cisys/")).toBe("cisys");
  });
});
