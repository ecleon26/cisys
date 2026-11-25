/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { formatDateTime } from "./i18n";

/** Segment used in build URLs when the build has no branch. Git branch names cannot contain "~". */
const NO_BRANCH = "~";

/** Path of the dedicated page for one build, for example /cisys/master/build/51. */
export function buildPath(name, branch, id) {
  return `/${encodeURIComponent(name)}/${encodeURIComponent(branch || NO_BRANCH)}/build/${id}`;
}

/** README Markdown for a project's build badge. Slashes in the branch stay as path separators. */
export function badgeMarkdown(apiUrl, origin, name, branch) {
  const api = new URL(apiUrl || "/api", origin).href.replace(/\/$/, "");
  const branchPath = branch.split("/").map(encodeURIComponent).join("/");
  const image = `${api}/badge/${encodeURIComponent(name)}/${branchPath}.svg`;
  const page = `${origin}/${encodeURIComponent(name)}/`;
  return `[![build](${image})](${page})`;
}

/** Turns the branch segment of a build URL back into a branch name ("" when the build has none). */
export function branchFromSegment(segment) {
  return segment === NO_BRANCH ? "" : segment;
}

/** Human-readable build duration, shared by the builds table, build page and log view. */
export function duration(build) {
  if (build.status === "QUEUED") return "queued";
  if (!build.finishedAt) return "running";
  if (!build.startedAt) return build.status === "CANCELLED" ? "—" : "unknown";
  const seconds = Math.max(
    0,
    Math.round((new Date(build.finishedAt) - new Date(build.startedAt)) / 1000),
  );
  const m = Math.floor(seconds / 60),
    s = seconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

export function shortRevision(revision) {
  return revision ? revision.slice(0, 7) : "—";
}

/** Label for a stored pull request identity, for example PR #12 or MR !12. */
export function pullRequestLabel(provider, number) {
  if (number == null) return null;
  return provider === "GITLAB" ? `MR !${number}` : `PR #${number}`;
}

/** Name of the review request itself, for example as a build detail field label. */
export function pullRequestKind(provider) {
  return provider === "GITLAB" ? "Merge request" : "Pull request";
}

/** Label for how a build was started. */
export function triggerLabel(trigger, provider) {
  if (trigger === "PULL_REQUEST")
    return provider === "GITLAB" ? "Merge request" : "Pull request";
  if (trigger === "POLL") return "Poll";
  return "Manual";
}

export function startedAt(build) {
  return build.startedAt ? formatDateTime(build.startedAt) : "Not started";
}
