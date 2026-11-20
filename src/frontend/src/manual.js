/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

const sources = import.meta.glob("../../../docs/manual/en/[0-9]*.md", {
  query: "?raw",
  import: "default",
  eager: true,
});
const images = import.meta.glob("../../../docs/manual/en/images/*", {
  query: "?url",
  import: "default",
  eager: true,
});

/** Removes the Pandoc-only parts: \newpage and attribute blocks such as { width=100% }. */
export function cleanPandoc(text) {
  return text
    .replace(/^\\newpage\s*$/gm, "")
    .replace(/(!\[[^\]]*\]\([^)]*\))\{[^}]*\}/g, "$1")
    .trim();
}

function group(number) {
  if (number >= 97) return "Appendix";
  if (number >= 70) return "Developers";
  return "User guide";
}

const DEFINITION = /^ {0,3}\[[^\]]+\]:\s+\S.*$/gm;

/**
 * Turns { path: markdown } into the ordered chapter list. Pandoc joins the files into one
 * document, so a file without a "# " heading continues the previous chapter and link
 * definitions such as those in 99-references.md apply to every chapter.
 */
export function parseChapters(files) {
  const parsed = Object.entries(files)
    .map(([path, text]) => {
      const match = /(\d+)-([^/]+)\.md$/.exec(path);
      return match && { number: Number(match[1]), slug: match[2], text };
    })
    .filter(Boolean)
    .sort((a, b) => a.number - b.number);
  const definitions = parsed
    .flatMap((f) => f.text.match(DEFINITION) ?? [])
    .join("\n");
  const chapters = [];
  for (const file of parsed) {
    const body = cleanPandoc(file.text.replace(DEFINITION, ""));
    const title = /^# (.+)$/m.exec(body)?.[1].trim();
    if (title)
      chapters.push({
        number: file.number,
        slug: file.slug,
        title,
        group: group(file.number),
        body,
      });
    // The front page is only YAML and comes before any chapter.
    else if (body && chapters.length > 0 && !body.startsWith("---"))
      chapters.at(-1).body += `\n\n${body}`;
  }
  return chapters
    .filter((c) => c.body !== `# ${c.title}`)
    .map((c) => ({ ...c, body: `${c.body}\n\n${definitions}`.trim() }));
}

export const chapters = parseChapters(sources);

export function chapterBySlug(slug) {
  return chapters.find((c) => c.slug === slug) ?? null;
}

export function imageUrl(src) {
  const name = src.replace(/^\.?\/?images\//, "");
  return images[`../../../docs/manual/en/images/${name}`] ?? src;
}

/** Anchor id for a heading, e.g. "Start a build" → "start-a-build". */
export function headingId(text) {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, "")
    .trim()
    .replace(/\s+/g, "-");
}
