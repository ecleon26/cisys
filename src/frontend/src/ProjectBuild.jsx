/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, Pin, PinOff, Square } from 'lucide-react';
import { Button } from './components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './components/ui/card';
import PageHeader from './components/layout/PageHeader';
import BuildStatus from './BuildStatus';
import { BuildLogPanel } from './BuildLogView';
import { duration, startedAt } from './build-format';

/** Dedicated page for one build, including the expanded log. */
/** `onNotFound()` is called when the build does not exist or the visitor may not see it. */
export default function ProjectBuild({ name, branch, buildId, load, onBack, onNotFound }) {
  // undefined while loading.
  const [build, setBuild] = useState(undefined);
  const [error, setError] = useState('');
  const [pinning, setPinning] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  const loadBuild = useCallback(async () => {
    try {
      setBuild(await load(`/project-pages/${encodeURIComponent(name)}/builds/${buildId}`));
      setError('');
    } catch (e) {
      if (e.status === 404) onNotFound();
      else setError(e.message || 'Unable to load the build.');
    }
  }, [load, name, buildId, onNotFound]);

  async function togglePin() {
    setPinning(true); setError('');
    try {
      await load(`/projects/${build.projectId}/builds/${build.id}/pin`, { method: build.pinned ? 'DELETE' : 'PUT' });
      await loadBuild();
    } catch (e) { setError(e.message || 'Unable to change the pin.'); }
    finally { setPinning(false); }
  }

  async function cancel() {
    setCancelling(true); setError('');
    try {
      await load(`/projects/${build.projectId}/builds/${build.id}/cancel`, { method: 'POST' });
      await loadBuild();
    } catch (e) { setError(e.message || 'Unable to cancel the build.'); }
    finally { setCancelling(false); }
  }

  useEffect(() => {
    const initial = setTimeout(loadBuild, 0);
    const timer = setInterval(loadBuild, 5000);
    return () => { clearTimeout(initial); clearInterval(timer); };
  }, [loadBuild]);

  if (build === undefined) {
    return <main id="project-build" className="page-container">{error
      ? <div className="error-message" role="alert">{error}</div>
      : <p className="loading-state" role="status">Loading build…</p>}</main>;
  }

  const finished = build.finishedAt ? new Date(build.finishedAt).toLocaleString() : 'Not finished';

  return (
    <main id="project-build" className="page-container">
      <PageHeader title={`Build #${build.id}`} actions={<>
        {build.canCancel && <Button type="button" variant="outline" size="icon" onClick={cancel} disabled={cancelling}
          title="Cancel" aria-label="Cancel build"><Square size={16} aria-hidden="true"/></Button>}
        {build.canPin && <Button type="button" variant="outline" size="icon" onClick={togglePin} disabled={pinning}
          title={build.pinned ? 'Unpin' : 'Pin'} aria-label={build.pinned ? 'Unpin build' : 'Pin build'}>
          {build.pinned ? <PinOff size={16} aria-hidden="true"/> : <Pin size={16} aria-hidden="true"/>}
        </Button>}
        <Button type="button" variant="outline" size="icon" onClick={onBack} title="Back to builds" aria-label="Back to builds"><ArrowLeft size={16} aria-hidden="true"/></Button>
      </>}/>
      {error && <div className="error-message" role="alert">{error}</div>}
      <Card className="project-card">
        <CardHeader>
          <CardTitle className="build-title">
            <BuildStatus status={build.status}/>
            <span>{build.status}</span>
          </CardTitle>
          <CardDescription>{branch ? `${name} · ${branch}` : name}</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="build-summary">
            <dt>Branch</dt>
            <dd>{build.branch || 'Unknown'}</dd>
            <dt>Commit</dt>
            <dd><code>{build.revision ?? 'Unknown'}</code></dd>
            <dt>Started</dt>
            <dd>{startedAt(build)}</dd>
            <dt>Finished</dt>
            <dd>{finished}</dd>
            <dt>Duration</dt>
            <dd>{duration(build)}</dd>
            <dt>Exit code</dt>
            <dd>{build.exitCode ?? '—'}</dd>
            <dt>Pinned</dt>
            <dd>{build.pinned ? 'Yes' : 'No'}</dd>
            <dt>Image</dt>
            <dd>{build.image ?? 'Unknown'}{build.imageDigest ? ` (${build.imageDigest})` : ''}</dd>
            <dt>Workflow</dt>
            <dd>{build.workflow ? <pre className="whitespace-pre-wrap">{build.workflow}</pre> : build.status === 'QUEUED' ? 'Not read yet' : 'None'}</dd>
          </dl>
        </CardContent>
      </Card>
      <Card className="project-card">
        <CardContent>
          <BuildLogPanel api={load} logPath={`/project-pages/${encodeURIComponent(name)}/builds/${build.id}/log`}
            build={build} onRefresh={loadBuild} showMeta={false} scrollable title="Log"/>
        </CardContent>
      </Card>
    </main>
  );
}
