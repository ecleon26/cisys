/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { branchFromSegment } from "./build-format";

// The manual lives at /manual, so no project may be named "manual".
const MANUAL = "manual";

/** Returns { slug } for /manual (slug null) or /manual/builds, otherwise null. */
export function manualFromPath(pathname) {
  const match = /^\/manual(?:\/([^/]*))?\/?$/i.exec(pathname);
  if (!match) return null;
  try {
    return { slug: match[1] ? decodeURIComponent(match[1]) : null };
  } catch {
    return { slug: null };
  }
}

export function manualPath(slug) {
  return slug ? `/${MANUAL}/${encodeURIComponent(slug)}` : `/${MANUAL}`;
}

function isManual(name) {
  return name.toLowerCase() === MANUAL;
}

/** Returns the project name for paths like /cisys/ (or /cisys), otherwise null. */
export function projectNameFromPath(pathname) {
  const match = /^\/([^/]+)\/?$/.exec(pathname);
  if (!match) return null;
  try {
    const name = decodeURIComponent(match[1]);
    return isManual(name) ? null : name;
  } catch {
    return null;
  }
}

/** Returns { name, branch, buildId } for paths like /cisys/master/build/51, otherwise null. */
export function projectBuildFromPath(pathname) {
  const match = /^\/([^/]+)\/([^/]+)\/build\/(\d+)\/?$/.exec(pathname);
  if (!match) return null;
  try {
    if (isManual(decodeURIComponent(match[1]))) return null;
    return {
      name: decodeURIComponent(match[1]),
      branch: branchFromSegment(decodeURIComponent(match[2])),
      buildId: Number(match[3]),
    };
  } catch {
    return null;
  }
}

export function projectPath(name) {
  return `/${encodeURIComponent(name)}/`;
}

/** Returns { name } for paths like /cisys/settings, otherwise null. */
export function projectSettingsFromPath(pathname) {
  const match = /^\/([^/]+)\/settings\/?$/.exec(pathname);
  if (!match) return null;
  try {
    const name = decodeURIComponent(match[1]);
    return isManual(name) ? null : { name };
  } catch {
    return null;
  }
}

export function projectSettingsPath(name) {
  return `${projectPath(name)}settings`;
}
