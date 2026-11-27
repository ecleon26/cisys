/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowLeft,
  ChevronDown,
  ChevronRight,
  Pin,
  PinOff,
  RotateCcw,
  Square,
} from "lucide-react";
import { Button } from "./components/ui/button";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "./components/ui/card";
import PageHeader from "./components/layout/PageHeader";
import BuildStatus from "./BuildStatus";
import BuildComments from "./BuildComments";
import BuildReports from "./BuildReports";
import BuildArtifacts from "./BuildArtifacts";
import { BuildLogPanel } from "./BuildLogView";
import {
  duration,
  pullRequestKind,
  pullRequestLabel,
  startedAt,
  triggerLabel,
} from "./build-format";
import { formatDateTime } from "./i18n";

/**
 * Text that may span several lines. More than one line is folded to its first line, with a
 * chevron to unfold it; one line is shown as it is.
 */
export function FoldedText({ text, label }) {
  const [open, setOpen] = useState(false);
  const lines = text.replace(/\n+$/, "").split("\n");
  if (lines.length <= 1) {
    return <pre className="folded-text-body">{text}</pre>;
  }
  return (
    <div className="folded-text">
      <button
        type="button"
        className="folded-text-toggle"
        aria-expanded={open}
        title={open ? `Hide ${label}` : `Show all ${lines.length} lines`}
        onClick={() => setOpen((o) => !o)}
      >
        {open ? (
          <ChevronDown size={14} aria-hidden="true" />
        ) : (
          <ChevronRight
            size={14}
            className="rtl:rotate-180"
            aria-hidden="true"
          />
        )}
        {open ? (
          <span className="sr-only">Hide {label}</span>
        ) : (
          <code className="folded-text-first">{lines[0]}</code>
        )}
      </button>
      {open && <pre className="folded-text-body">{text}</pre>}
    </div>
  );
}

/** Dedicated page for one build, including the expanded log. */
/** `onNotFound()` is called when the build does not exist or the visitor may not see it. */
export default function ProjectBuild({
  name,
  branch,
  buildId,
  load,
  onBack,
  onOpenBuild,
  onNotFound,
}) {
  // undefined while loading.
  const [build, setBuild] = useState(undefined);
  const [error, setError] = useState("");
  const [pinning, setPinning] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [rerunning, setRerunning] = useState(false);
  const requestRef = useRef(0);

  const loadBuild = useCallback(async () => {
    const request = ++requestRef.current;
    try {
      const data = await load(
        `/project-pages/${encodeURIComponent(name)}/builds/${buildId}`,
      );
      // A slow, older response must not overwrite or 404-redirect a newly opened build.
      if (request !== requestRef.current) return;
      setBuild(data);
      setError("");
    } catch (e) {
      if (request !== requestRef.current) return;
      if (e.status === 404) onNotFound();
      else setError(e.message || "Unable to load the build.");
    }
  }, [load, name, buildId, onNotFound]);

  async function togglePin() {
    setPinning(true);
    setError("");
    try {
      await load(`/projects/${build.projectId}/builds/${build.id}/pin`, {
        method: build.pinned ? "DELETE" : "PUT",
      });
      await loadBuild();
    } catch (e) {
      setError(e.message || "Unable to change the pin.");
    } finally {
      setPinning(false);
    }
  }

  async function cancel() {
    setCancelling(true);
    setError("");
    try {
      await load(`/projects/${build.projectId}/builds/${build.id}/cancel`, {
        method: "POST",
      });
      await loadBuild();
    } catch (e) {
      setError(e.message || "Unable to cancel the build.");
    } finally {
      setCancelling(false);
    }
  }

  async function rerun() {
    setRerunning(true);
    setError("");
    try {
      const queued = await load(
        `/projects/${build.projectId}/builds/${build.id}/rerun`,
        { method: "POST" },
      );
      if (onOpenBuild) onOpenBuild(name, queued.branch, queued.id);
      else await loadBuild();
    } catch (e) {
      setError(e.message || "Unable to run the build again.");
    } finally {
      setRerunning(false);
    }
  }

  useEffect(() => {
    requestRef.current += 1;
    setBuild(undefined);
    setError("");
    const initial = setTimeout(loadBuild, 0);
    const timer = setInterval(loadBuild, 5000);
    return () => {
      requestRef.current += 1;
      clearTimeout(initial);
      clearInterval(timer);
    };
  }, [loadBuild]);

  if (build === undefined) {
    return (
      <main id="project-build" className="page-container">
        {error ? (
          <div className="error-message" role="alert">
            {error}
          </div>
        ) : (
          <p className="loading-state" role="status">
            Loading build…
          </p>
        )}
      </main>
    );
  }

  const finished = build.finishedAt
    ? formatDateTime(build.finishedAt)
    : "Not finished";

  return (
    <main id="project-build" className="page-container">
      <PageHeader
        title={`Build #${build.number}`}
        actions={
          <>
            {build.canCancel && (
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={cancel}
                disabled={cancelling}
                title="Cancel"
                aria-label="Cancel build"
              >
                <Square size={16} aria-hidden="true" />
              </Button>
            )}
            {build.canRerun && (
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={rerun}
                disabled={rerunning}
                title="Re-run"
                aria-label="Re-run build"
              >
                <RotateCcw size={16} aria-hidden="true" />
              </Button>
            )}
            {build.canPin && (
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={togglePin}
                disabled={pinning}
                title={build.pinned ? "Unpin" : "Pin"}
                aria-label={build.pinned ? "Unpin build" : "Pin build"}
              >
                {build.pinned ? (
                  <PinOff size={16} aria-hidden="true" />
                ) : (
                  <Pin size={16} aria-hidden="true" />
                )}
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={onBack}
              title="Back to builds"
              aria-label="Back to builds"
            >
              <ArrowLeft
                size={16}
                className="rtl:rotate-180"
                aria-hidden="true"
              />
            </Button>
          </>
        }
      />
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle className="build-title">
            <BuildStatus status={build.status} />
            <span>{build.status}</span>
          </CardTitle>
          <CardDescription>
            {branch ? `${name} · ${branch}` : name}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="build-summary">
            <dt>Branch</dt>
            <dd>{build.branch || "Unknown"}</dd>
            <dt>Trigger</dt>
            <dd>{triggerLabel(build.trigger, build.pullRequestProvider)}</dd>
            {build.pullRequest != null && (
              <>
                <dt>{pullRequestKind(build.pullRequestProvider)}</dt>
                <dd>
                  {pullRequestLabel(
                    build.pullRequestProvider,
                    build.pullRequest,
                  )}
                </dd>
              </>
            )}
            <dt>Commit</dt>
            <dd>
              <code>{build.revision ?? "Unknown"}</code>
            </dd>
            <dt>Started</dt>
            <dd>{startedAt(build)}</dd>
            <dt>Finished</dt>
            <dd>{finished}</dd>
            <dt>Duration</dt>
            <dd>{duration(build)}</dd>
            <dt>Exit code</dt>
            <dd>{build.exitCode ?? "—"}</dd>
            <dt>Pinned</dt>
            <dd>{build.pinned ? "Yes" : "No"}</dd>
            <dt>Image</dt>
            <dd>
              {build.image ?? "Unknown"}
              {build.imageDigest ? ` (${build.imageDigest})` : ""}
            </dd>
            <dt>Workflow</dt>
            <dd>
              {build.workflow ? (
                <FoldedText text={build.workflow} label="workflow" />
              ) : build.status === "QUEUED" ? (
                "Not read yet"
              ) : (
                "None"
              )}
            </dd>
            <dt>Dockerfile</dt>
            <dd>
              {build.dockerfile ? (
                <FoldedText text={build.dockerfile} label="Dockerfile" />
              ) : build.status === "QUEUED" ? (
                "Not generated yet"
              ) : (
                "None"
              )}
            </dd>
          </dl>
        </CardContent>
      </Card>
      <Card className="project-card">
        <CardContent>
          <BuildLogPanel
            api={load}
            logPath={`/project-pages/${encodeURIComponent(name)}/builds/${build.id}/log`}
            build={build}
            projectName={name}
            onRefresh={loadBuild}
            showMeta={false}
            scrollable
            title="Log"
          />
        </CardContent>
      </Card>
      <BuildReports name={name} buildId={build.id} load={load} />
      <BuildArtifacts name={name} buildId={build.id} load={load} />
      <BuildComments
        name={name}
        projectId={build.projectId}
        buildId={build.id}
        canComment={build.canComment}
        load={load}
      />
    </main>
  );
}
