/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useRef, useState } from "react";
import {
  Check,
  Copy,
  GitBranch,
  Hammer,
  LockKeyhole,
  LockKeyholeOpen,
  Pin,
} from "lucide-react";
import { Badge } from "./components/ui/badge";
import { Button } from "./components/ui/button";
import { Card, CardContent } from "./components/ui/card";
import PageHeader from "./components/layout/PageHeader";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "./components/ui/tabs";
import BuildStatus from "./BuildStatus";
import ProjectCommits from "./ProjectCommits";
import {
  badgeMarkdown,
  buildPath,
  duration,
  pullRequestLabel,
  shortRevision,
  startedAt,
  triggerLabel,
} from "./build-format";

/**
 * Builds of one project, addressed by name. `load(path)` fetches JSON from the API, with or without a token.
 * The API answers 404 both for unknown projects and for private projects the visitor may not see;
 * either way `onNotFound()` is called. Render it with `key={name}` so state resets per project.
 * `onBuild(projectId, branch)` starts a build; without it no Build buttons are shown.
 */
export default function ProjectBuilds({
  name,
  load,
  apiUrl = "",
  onOpenBuild,
  onBuild,
  onNotFound,
  initialBranch = "",
  tab = "builds",
  onTabChange = () => {},
}) {
  // undefined while loading.
  const [page, setPage] = useState(undefined);
  const [error, setError] = useState("");
  const [branches, setBranches] = useState(null);
  const [branchesError, setBranchesError] = useState(false);
  const [selectedBranch, setSelectedBranch] = useState(initialBranch);
  const [buildsBranch, setBuildsBranch] = useState("");
  const [startingBranch, setStartingBranch] = useState(null);
  const [reload, setReload] = useState(0);
  const [badgeCopied, setBadgeCopied] = useState(false);
  const projectId = page?.project?.id;
  // Latest page, so the polling loop can decide whether a build is still active without restarting.
  const pageRef = useRef(undefined);
  // main is the default branch; older repositories still use master. There is no "all branches" view.
  const branchNames = branches || [];
  const defaultBranch = branchNames.includes("main")
    ? "main"
    : branchNames.includes("master")
      ? "master"
      : branchNames[0] || "";
  const activeBranch = selectedBranch || defaultBranch;

  useEffect(() => {
    let active = true;
    let lastFetch = 0;
    const branchQuery = activeBranch
      ? `?branch=${encodeURIComponent(activeBranch)}`
      : "";
    const fetchPage = async () => {
      lastFetch = Date.now();
      try {
        const data = await load(
          `/project-pages/${encodeURIComponent(name)}${branchQuery}`,
        );
        if (active) {
          pageRef.current = data;
          setPage(data);
          setBuildsBranch(activeBranch);
          setError("");
        }
      } catch (e) {
        if (!active) return;
        if (e.status === 404) {
          pageRef.current = null;
          setPage(null);
          onNotFound();
        } else setError(e.message || "Unable to load the project.");
      }
    };
    void fetchPage();
    // Poll only while the tab is visible, and back off when no build is running.
    const timer = setInterval(() => {
      if (document.hidden) return;
      const builds = pageRef.current?.builds ?? [];
      const activeBuild = builds.some(
        (b) => b.status === "RUNNING" || b.status === "QUEUED",
      );
      if (activeBuild || Date.now() - lastFetch >= 30000) void fetchPage();
    }, 5000);
    const onVisibility = () => {
      if (!document.hidden) void fetchPage();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      active = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [name, load, activeBranch, reload, onNotFound]);

  // Branches are loaded once per project, not on every build refresh. Public projects read the
  // repository; private projects list the branches that already have builds.
  useEffect(() => {
    if (projectId == null) return undefined;
    let active = true;
    load(`/project-pages/${encodeURIComponent(name)}/branches`)
      .then((data) => {
        if (active) {
          setBranches(Array.isArray(data.branches) ? data.branches : []);
          setBranchesError(false);
        }
      })
      .catch(() => {
        if (active) {
          setBranches([]);
          setBranchesError(true);
        }
      });
    return () => {
      active = false;
    };
  }, [name, load, projectId]);

  if (page === undefined) {
    return (
      <main id="project-builds" className="page-container">
        {error ? (
          <div className="error-message" role="alert">
            {error}
          </div>
        ) : (
          <p className="loading-state" role="status">
            Loading project…
          </p>
        )}
      </main>
    );
  }

  const { project, builds } = page;
  // While the branches load, or while a branch-filtered fetch is in flight, do not show the previous
  // unfiltered builds. Otherwise a build from another branch flashes on screen and then disappears.
  const buildsReady = branches !== null && buildsBranch === activeBranch;
  const startBuild = async (branch) => {
    setStartingBranch(branch);
    try {
      await onBuild(project.id, branch);
      setError("");
      setSelectedBranch(branch);
      setReload((n) => n + 1);
    } catch (e) {
      setError(e.message || "Unable to start the build.");
    } finally {
      setStartingBranch(null);
    }
  };
  const copyBadge = async () => {
    try {
      await navigator.clipboard.writeText(
        badgeMarkdown(
          apiUrl,
          window.location.origin,
          project.name,
          activeBranch,
        ),
      );
      setBadgeCopied(true);
      window.setTimeout(() => setBadgeCopied(false), 1500);
    } catch {
      setError("Unable to copy the badge Markdown.");
    }
  };
  const openBuild = (build) =>
    onOpenBuild?.(project.name, build.branch || activeBranch, build.id);

  return (
    <main id="project-builds" className="page-container">
      <PageHeader
        className="mb-2"
        title={project.name}
        actions={
          <>
            <Badge variant="outline">
              {project.privateRepository ? (
                <LockKeyhole
                  size={12}
                  className="text-red-600 dark:text-red-400"
                  aria-hidden="true"
                />
              ) : (
                <LockKeyholeOpen
                  size={12}
                  className="text-green-600 dark:text-green-400"
                  aria-hidden="true"
                />
              )}
              {project.privateRepository ? "Private" : "Public"}
            </Badge>
            {!project.privateRepository && activeBranch && (
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => void copyBadge()}
              >
                {badgeCopied ? (
                  <Check size={14} aria-hidden="true" />
                ) : (
                  <Copy size={14} aria-hidden="true" />
                )}
                {badgeCopied ? "Copied" : "Copy badge Markdown"}
              </Button>
            )}
          </>
        }
      />
      <p className="project-url project-repository">{project.repositoryUrl}</p>
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      <div className="project-layout">
        <aside className="branch-panel" aria-label="Branches">
          <h2 className="branch-panel-title">
            <GitBranch size={14} aria-hidden="true" /> Branches
          </h2>
          {branches === null && !branchesError ? (
            <p className="branch-panel-note">Loading branches…</p>
          ) : branchNames.length === 0 && !branchesError ? (
            <p className="branch-panel-note">No branches found.</p>
          ) : (
            <ul className="branch-list">
              {branchNames.map((branch) => (
                <li key={branch} className="branch-item">
                  <button
                    type="button"
                    className="branch-option"
                    aria-current={activeBranch === branch ? "page" : undefined}
                    onClick={() => setSelectedBranch(branch)}
                  >
                    {branch}
                  </button>
                  {onBuild && (
                    <button
                      type="button"
                      className="branch-build"
                      title={`Build ${branch} with .cisys/workflows/ci.yml`}
                      aria-label={`Build ${branch}`}
                      disabled={startingBranch !== null}
                      onClick={() => void startBuild(branch)}
                    >
                      <Hammer size={16} aria-hidden="true" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {branchesError && (
            <p className="branch-panel-note">Branches unavailable</p>
          )}
        </aside>
        <Tabs value={tab} onValueChange={onTabChange} className="project-tabs">
          <TabsList>
            <TabsTrigger value="builds">Builds</TabsTrigger>
            <TabsTrigger value="commits">Commits</TabsTrigger>
          </TabsList>
          <TabsContent value="builds">
            <Card className="project-card builds-panel">
              <CardContent>
                {!buildsReady ? (
                  <p className="loading-state" role="status">
                    Loading builds…
                  </p>
                ) : builds.length === 0 ? (
                  <p>No builds</p>
                ) : (
                  <div className="builds-table-wrap">
                    <table className="builds-table">
                      <thead>
                        <tr>
                          <th scope="col">
                            <span className="sr-only">Status</span>
                          </th>
                          <th scope="col">Build</th>
                          <th scope="col">Branch</th>
                          <th scope="col">Commit</th>
                          <th scope="col">Trigger</th>
                          <th scope="col">Started</th>
                          <th scope="col">Duration</th>
                        </tr>
                      </thead>
                      <tbody>
                        {builds.map((b) => (
                          <tr
                            key={b.id}
                            className={
                              onOpenBuild ? "builds-row-link" : undefined
                            }
                            onClick={
                              onOpenBuild ? () => openBuild(b) : undefined
                            }
                          >
                            <td className="builds-status-cell">
                              <BuildStatus status={b.status} />
                            </td>
                            <td>
                              <a
                                className="build-link"
                                href={buildPath(
                                  project.name,
                                  b.branch || activeBranch,
                                  b.id,
                                )}
                                onClick={(event) => {
                                  if (!onOpenBuild) return;
                                  event.preventDefault();
                                  event.stopPropagation();
                                  openBuild(b);
                                }}
                              >
                                #{b.number}
                              </a>
                              {b.pinned && (
                                <Pin
                                  className="build-pin"
                                  size={14}
                                  role="img"
                                  aria-label="Pinned"
                                />
                              )}
                            </td>
                            <td className="build-branch">
                              {b.branch || "—"}
                              {b.pullRequest != null && (
                                <span className="build-meta">
                                  {" "}
                                  {pullRequestLabel(
                                    b.pullRequestProvider,
                                    b.pullRequest,
                                  )}
                                </span>
                              )}
                            </td>
                            <td>
                              <code
                                className="build-commit"
                                title={b.revision ?? undefined}
                              >
                                {shortRevision(b.revision)}
                              </code>
                            </td>
                            <td className="build-meta">
                              {triggerLabel(b.trigger, b.pullRequestProvider)}
                            </td>
                            <td className="build-meta">{startedAt(b)}</td>
                            <td className="build-meta">{duration(b)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </CardContent>
            </Card>
          </TabsContent>
          <TabsContent value="commits">
            <ProjectCommits
              name={project.name}
              branch={activeBranch}
              load={load}
              onOpenBuild={onOpenBuild}
            />
          </TabsContent>
        </Tabs>
      </div>
    </main>
  );
}
