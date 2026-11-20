/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import {
  chapterBySlug,
  chapters,
  cleanPandoc,
  headingId,
  parseChapters,
} from "./manual";

const NEWPAGE = String.raw`\newpage`;

describe("manual", () => {
  it("strips the Pandoc-only syntax", () => {
    expect(
      cleanPandoc(
        NEWPAGE + "\n\n# Builds\n\n![Shot](images/b.png){ width=100% }",
      ),
    ).toBe("# Builds\n\n![Shot](images/b.png)");
  });

  it("joins the files like Pandoc", () => {
    const parsed = parseChapters({
      "x/05-builds.md": NEWPAGE + "\n\n# Builds\n\nSee [Quarkus][quarkus].",
      "x/00-frontpage.md": "---\ntitle: cisys\n---",
      "x/70-dev.md": NEWPAGE + "\n\n# Developer information",
      "x/71-git.md": "## Git guide\n\nText",
      "x/72-architecture.md": "# Architecture\n\nText",
      "x/01-introduction.md": "# Introduction\n\nText",
      "x/03-empty.md": "# Empty",
      "x/98-licenses.md": "# Licenses\n\nText",
      "x/99-references.md": "[quarkus]: https://quarkus.io/",
    });
    expect(parsed.map((c) => [c.slug, c.title, c.group])).toEqual([
      ["introduction", "Introduction", "User guide"],
      ["builds", "Builds", "User guide"],
      ["dev", "Developer information", "Developers"],
      ["architecture", "Architecture", "Developers"],
      ["licenses", "Licenses", "Appendix"],
    ]);
    expect(parsed[2].body).toContain("## Git guide");
    expect(parsed[1].body).toContain("[quarkus]: https://quarkus.io/");
    expect(parsed[4].body).not.toContain("title: cisys");
  });

  it("bundles the chapters from docs/manual/en", () => {
    expect(chapters[0].slug).toBe("introduction");
    expect(chapterBySlug("builds").title).toBe("Builds");
    expect(chapterBySlug("frontpage")).toBeNull();
    expect(chapterBySlug("references")).toBeNull();
    expect(chapterBySlug("dev").body).toContain("## Git guide");
    expect(chapters.some((c) => c.body.includes(NEWPAGE))).toBe(false);
  });

  it("makes heading anchors", () => {
    expect(headingId("Start a build")).toBe("start-a-build");
    expect(headingId("Roles & access")).toBe("roles-access");
  });
});
