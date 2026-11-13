/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

/** Segment used in build URLs when the build has no branch. Git branch names cannot contain "~". */
const NO_BRANCH = '~';

/** Path of the dedicated page for one build, for example /cisys/master/build/51. */
export function buildPath(name, branch, id) {
  return `/${encodeURIComponent(name)}/${encodeURIComponent(branch || NO_BRANCH)}/build/${id}`;
}

/** Turns the branch segment of a build URL back into a branch name ("" when the build has none). */
export function branchFromSegment(segment) {
  return segment === NO_BRANCH ? '' : segment;
}

/** Human-readable build duration, shared by the builds table, build page and log view. */
export function duration(build) {
  if (build.status === 'QUEUED') return 'queued';
  if (!build.finishedAt) return 'running';
  if (!build.startedAt) return build.status === 'CANCELLED' ? '—' : 'unknown';
  const seconds = Math.max(0, Math.round((new Date(build.finishedAt) - new Date(build.startedAt)) / 1000));
  const m = Math.floor(seconds / 60), s = seconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

export function shortRevision(revision) {
  return revision ? revision.slice(0, 7) : '—';
}

export function startedAt(build) {
  return build.startedAt ? new Date(build.startedAt).toLocaleString() : 'Not started';
}
