/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeHighlight from "rehype-highlight";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { chapters as allChapters, headingId, imageUrl } from "./manual";
import { manualPath } from "./project-paths";

function textOf(children) {
  return React.Children.toArray(children)
    .map((c) =>
      typeof c === "string" || typeof c === "number"
        ? String(c)
        : textOf(c?.props?.children),
    )
    .join("");
}

function heading(Tag) {
  return function Heading({ children }) {
    return <Tag id={headingId(textOf(children))}>{children}</Tag>;
  };
}

const components = {
  h2: heading("h2"),
  h3: heading("h3"),
  h4: heading("h4"),
  img: ({ src, alt }) => <img src={imageUrl(src)} alt={alt} />,
  a: ({ href, children }) =>
    /^https?:/.test(href) ? (
      <a href={href} target="_blank" rel="noreferrer">
        {children}
      </a>
    ) : (
      <a href={href}>{children}</a>
    ),
};

export default function ManualPage({ slug, onOpen, chapters = allChapters }) {
  const index = Math.max(
    0,
    chapters.findIndex((c) => c.slug === slug),
  );
  const chapter = chapters[index];
  const prev = chapters[index - 1];
  const next = chapters[index + 1];

  useEffect(() => {
    if (!window.location.hash) window.scrollTo?.(0, 0);
  }, [chapter?.slug]);

  if (!chapter) return null;

  const link = (c, children, className) => (
    <a
      href={manualPath(c.slug)}
      className={className}
      aria-current={c.slug === chapter.slug ? "page" : undefined}
      onClick={(event) => {
        event.preventDefault();
        onOpen(c.slug);
      }}
    >
      {children}
    </a>
  );

  const groups = [...new Set(chapters.map((c) => c.group))];

  return (
    <main id="manual" className="page-container manual-layout">
      <nav className="manual-nav" aria-label="Manual">
        {groups.map((g) => (
          <section key={g}>
            <h2>{g}</h2>
            <ul>
              {chapters
                .filter((c) => c.group === g)
                .map((c) => (
                  <li key={c.slug}>{link(c, c.title)}</li>
                ))}
            </ul>
          </section>
        ))}
      </nav>
      <article className="manual">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeHighlight]}
          components={components}
        >
          {chapter.body}
        </ReactMarkdown>
        <div className="manual-pager">
          {prev ? (
            link(
              prev,
              <>
                <ChevronLeft size={16} aria-hidden="true" /> {prev.title}
              </>,
              "manual-prev",
            )
          ) : (
            <span />
          )}
          {next &&
            link(
              next,
              <>
                {next.title} <ChevronRight size={16} aria-hidden="true" />
              </>,
              "manual-next",
            )}
        </div>
      </article>
    </main>
  );
}
