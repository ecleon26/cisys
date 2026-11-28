/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Check, Copy, Save, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "./components/ui/card";
import PageHeader from "./components/layout/PageHeader";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "./components/ui/tabs";
import ProjectMembers from "./ProjectMembers";
import { diffMembers, toMap } from "./member-diff";
import { badgeMarkdown } from "./build-format";
import WebhooksPanel from "./WebhooksPanel";
import { body as webhookBody, diffWebhooks, toDraft } from "./webhook-diff";

const USERS_PAGE = 100;

/**
 * Dedicated page for a project's settings.
 * Both tabs stage changes locally and apply them via the page Save button, reusing the
 * existing team endpoints. Discard restores the chosen tab's baseline. The selected tab is
 * page state only, like the Settings page; it is not reflected in the URL.
 */
export default function ProjectSettings({
  project,
  api,
  apiUrl = "",
  isAdmin,
  user,
  teams = [],
  onSaved,
  onDirtyChange,
}) {
  const { t } = useTranslation();
  const [tab, setTab] = useState("general");
  const [name, setName] = useState(project.name);
  const [url, setUrl] = useState(project.repositoryUrl);
  const [priv, setPriv] = useState(project.privateRepository);
  const [credential, setCredential] = useState(project.credentialRef || "");
  const [cache, setCache] = useState(!!project.cache);
  const [teamId, setTeamId] = useState(
    project.teamId == null ? "" : String(project.teamId),
  );
  // Last-saved snapshot of the repository fields; drives change detection.
  const [repoSnapshot, setRepoSnapshot] = useState({
    name: project.name,
    url: project.repositoryUrl,
    privateRepository: project.privateRepository,
    credential: (project.credentialRef || "").trim(),
    cache: !!project.cache,
    teamId: project.teamId == null ? "" : String(project.teamId),
  });
  const [originalMembers, setOriginalMembers] = useState(null); // null while loading / before Team is opened
  const [draftMembers, setDraftMembers] = useState(null);
  const [memberLoadError, setMemberLoadError] = useState("");
  // Usernames offered by the Team tab's Add drop-down: the users the Users page lists.
  const [users, setUsers] = useState(null);
  const [originalHooks, setOriginalHooks] = useState(null);
  const [draftHooks, setDraftHooks] = useState([]);
  const [deliveries, setDeliveries] = useState(null);
  const [hookLoadError, setHookLoadError] = useState("");
  // Secrets are only returned when created or rotated, so they are shown until the page is left.
  const [secrets, setSecrets] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [badgeCopied, setBadgeCopied] = useState(false);

  // Team data loads lazily, the first time the Team tab is opened.
  // `reloadMembers` re-triggers it after a load failure (e.g. bad connection).
  function reloadMembers() {
    setOriginalMembers(null);
    setMemberLoadError("");
  }

  useEffect(() => {
    if (tab !== "team" || originalMembers !== null) return;
    let active = true;
    api(`/projects/${project.id}/team`)
      .then((data) => {
        if (!active) return;
        const list = Array.isArray(data) ? data : [];
        setOriginalMembers(list);
        setDraftMembers(toMap(list));
        setMemberLoadError("");
      })
      .catch((e) => {
        if (!active) return;
        setOriginalMembers([]);
        setDraftMembers(new Map());
        setMemberLoadError(
          e?.status === 403
            ? "You do not have permission to view this project's members."
            : e?.status === 404
              ? "This project no longer exists."
              : e?.message || "Unable to load the members.",
        );
      });
    return () => {
      active = false;
    };
  }, [tab, api, project.id, originalMembers]);

  async function loadHooks() {
    const [list, recent] = await Promise.all([
      api(`/projects/${project.id}/webhooks`),
      api(`/projects/${project.id}/webhooks/deliveries`),
    ]);
    const hooks = Array.isArray(list) ? list : [];
    setOriginalHooks(hooks);
    setDraftHooks(toDraft(hooks));
    setDeliveries(Array.isArray(recent) ? recent : []);
    setHookLoadError("");
  }

  useEffect(() => {
    if (tab !== "webhooks" || originalHooks !== null) return;
    let active = true;
    loadHooks().catch((e) => {
      if (!active) return;
      setOriginalHooks([]);
      setDraftHooks([]);
      setHookLoadError(
        e?.status === 403
          ? t("webhooks.forbidden")
          : e?.message || t("webhooks.loadError"),
      );
    });
    return () => {
      active = false;
    };
    // loadHooks only reads props that are already dependencies.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, api, project.id, originalHooks]);

  // The users that can be added load once, the first time the Team tab is opened.
  useEffect(() => {
    if (tab !== "team" || users !== null) return;
    let active = true;
    (async () => {
      const names = [];
      for (let first = 0; ;) {
        const page = await api(`/users?first=${first}&max=${USERS_PAGE}`);
        const list = Array.isArray(page?.users) ? page.users : [];
        names.push(...list.map((u) => u.username));
        first += list.length;
        if (list.length === 0 || first >= (page?.total ?? 0)) break;
      }
      return names;
    })()
      .then((names) => active && setUsers(names))
      .catch(() => active && setUsers([]));
    return () => {
      active = false;
    };
  }, [tab, api, users]);

  const repoDirty =
    name !== repoSnapshot.name ||
    url !== repoSnapshot.url ||
    priv !== repoSnapshot.privateRepository ||
    (isAdmin && credential.trim() !== repoSnapshot.credential) ||
    cache !== repoSnapshot.cache;
  const teamDirty = teamId !== repoSnapshot.teamId;
  const memberPlan =
    originalMembers && draftMembers
      ? diffMembers(toMap(originalMembers), draftMembers)
      : null;
  const membersDirty = memberPlan?.changed ?? false;
  const hookPlan = originalHooks
    ? diffWebhooks(originalHooks, draftHooks)
    : null;
  const hooksDirty = hookPlan?.changed ?? false;
  const dirty = repoDirty || teamDirty || membersDirty || hooksDirty;

  // Warn before accidentally leaving with unsaved changes.
  const dirtyRef = React.useRef(dirty);
  useEffect(() => {
    dirtyRef.current = dirty;
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    const onBeforeUnload = (event) => {
      if (!dirtyRef.current) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, []);

  // Only admins can set a project credential, and only a private project can have one.
  const showCredential = isAdmin && priv;

  // The badge points at the default branch: main, else master, else the first branch listed.
  async function copyBadge() {
    try {
      const data = await api(
        `/project-pages/${encodeURIComponent(repoSnapshot.name)}/branches`,
      );
      const branches = Array.isArray(data?.branches) ? data.branches : [];
      const branch = branches.includes("main")
        ? "main"
        : branches.includes("master")
          ? "master"
          : branches[0];
      if (!branch) throw new Error("no branches");
      await navigator.clipboard.writeText(
        badgeMarkdown(
          apiUrl,
          window.location.origin,
          repoSnapshot.name,
          branch,
        ),
      );
      setBadgeCopied(true);
      window.setTimeout(() => setBadgeCopied(false), 1500);
    } catch {
      setError("Unable to copy the badge Markdown.");
    }
  }

  function switchTab(next) {
    if (next === tab) return;
    setTab(next);
  }

  async function repoPut() {
    const body = {
      name,
      repositoryUrl: url,
      privateRepository: priv,
      cache,
    };
    // A lead's save leaves the credential untouched.
    if (isAdmin)
      body.credentialRef = priv && credential.trim() ? credential.trim() : null;
    return api(`/projects/${project.id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  /** Applies a list of sequential member ops; stops at the first failure and re-syncs. */
  async function applyMemberOps(ops) {
    for (let i = 0; i < ops.length; i += 1) {
      const op = ops[i];
      try {
        await op.run();
      } catch (e) {
        // Re-load the list so already-applied operations drop out of pending.
        try {
          const fresh = await api(`/projects/${project.id}/team`);
          if (Array.isArray(fresh)) setOriginalMembers(fresh);
        } catch {
          // The list may be temporarily unavailable; leave the baseline untouched.
        }
        const parts = [];
        if (i > 0) parts.push(`${i} of ${ops.length} change(s) were applied`);
        setError(
          `${parts.join(", ")}${parts.length ? "; " : ""}failed while ${op.label}: ${
            e?.message || "request failed"
          }. Your remaining changes are kept — press Save to retry.`,
        );
        return;
      }
    }
    try {
      const fresh = await api(`/projects/${project.id}/team`);
      if (!Array.isArray(fresh))
        throw new Error("The server did not return a member list.");
      setOriginalMembers(fresh);
      setDraftMembers(toMap(fresh));
      setMemberLoadError("");
      setSuccess(t("settings.teamSaved"));
      setError("");
    } catch (e) {
      setError(
        t("settings.teamRefreshedError", {
          error: e?.message || "request failed",
        }),
      );
    }
  }

  function applySaved(saved, syncTeam) {
    setRepoSnapshot({
      name: saved.name,
      url: saved.repositoryUrl,
      privateRepository: saved.privateRepository,
      credential: (saved.credentialRef || "").trim(),
      cache: !!saved.cache,
      teamId: saved.teamId == null ? "" : String(saved.teamId),
    });
    setName(saved.name);
    setUrl(saved.repositoryUrl);
    setPriv(saved.privateRepository);
    setCredential(saved.credentialRef || "");
    setCache(!!saved.cache);
    if (syncTeam) {
      setTeamId(saved.teamId == null ? "" : String(saved.teamId));
    }
    onSaved(saved);
  }

  async function saveHooks(plan) {
    const created = [];
    try {
      for (const w of plan.deletes)
        await api(`/projects/${project.id}/webhooks/${w.id}`, {
          method: "DELETE",
        });
      for (const row of plan.updates)
        await api(`/projects/${project.id}/webhooks/${row.id}`, {
          method: "PUT",
          body: webhookBody(row),
        });
      for (const row of plan.creates) {
        const saved = await api(`/projects/${project.id}/webhooks`, {
          method: "POST",
          body: webhookBody(row),
        });
        created.push({ id: saved.id, url: saved.url, secret: saved.secret });
      }
      setSuccess(t("webhooks.saved"));
    } catch (e) {
      setError(e?.message || t("webhooks.saveError"));
    }
    if (created.length) setSecrets((prev) => [...prev, ...created]);
    try {
      await loadHooks();
    } catch {
      // Keep the draft when the list cannot be refreshed.
    }
  }

  async function rotateSecret(row) {
    setError("");
    setSuccess("");
    try {
      const saved = await api(
        `/projects/${project.id}/webhooks/${row.id}/secret`,
        { method: "POST" },
      );
      setSecrets((prev) => [
        ...prev.filter((s) => s.id !== saved.id),
        { id: saved.id, url: saved.url, secret: saved.secret },
      ]);
    } catch (e) {
      setError(e?.message || t("webhooks.rotateError"));
    }
  }

  async function submit(event) {
    event?.preventDefault();
    if (busy || !dirty) return;
    setBusy(true);
    setError("");
    setSuccess("");
    if (tab === "general") {
      let committed = null;
      try {
        if (repoDirty) {
          committed = await repoPut();
        }
        if (teamDirty) {
          committed = await api(`/projects/${project.id}/team-assignment`, {
            method: "PUT",
            body: JSON.stringify({ teamId: teamId ? Number(teamId) : null }),
          });
        }
        if (committed) {
          applySaved(committed, true);
        }
      } catch (e) {
        if (committed) {
          // repoPut() already persisted on the server before team-assignment failed:
          // commit the saved repository snapshot and notify the parent while keeping
          // the unsaved team selection in place so the user can retry or discard.
          applySaved(committed, false);
        }
        setError(e.message || "Unable to save the project.");
      } finally {
        setBusy(false);
      }
      return;
    }
    if (tab === "webhooks") {
      if (hookPlan) await saveHooks(hookPlan);
      setBusy(false);
      return;
    }
    const plan =
      originalMembers && draftMembers
        ? diffMembers(toMap(originalMembers), draftMembers)
        : null;
    const ops = [];
    for (const add of plan?.adds ?? [])
      ops.push({
        label: `adding '${add.username}' as ${add.role.toLowerCase()}`,
        run: () =>
          api(`/projects/${project.id}/team`, {
            method: "POST",
            body: JSON.stringify({ username: add.username, role: add.role }),
          }),
      });
    for (const ch of plan?.promotes ?? [])
      ops.push({
        label: `promoting '${ch.username}' to lead`,
        run: () =>
          api(
            `/projects/${project.id}/team/${encodeURIComponent(ch.username)}`,
            {
              method: "PUT",
              body: JSON.stringify({ role: "LEAD" }),
            },
          ),
      });
    for (const ch of plan?.demotes ?? [])
      ops.push({
        label: `demoting '${ch.username}' to member`,
        run: () =>
          api(
            `/projects/${project.id}/team/${encodeURIComponent(ch.username)}`,
            {
              method: "PUT",
              body: JSON.stringify({ role: "MEMBER" }),
            },
          ),
      });
    for (const username of plan?.removals ?? [])
      ops.push({
        label: `removing '${username}'`,
        run: () =>
          api(`/projects/${project.id}/team/${encodeURIComponent(username)}`, {
            method: "DELETE",
          }),
      });
    await applyMemberOps(ops);
    setBusy(false);
  }

  function discard() {
    if (busy) return;
    setSuccess("");
    if (tab === "general") {
      setName(repoSnapshot.name);
      setUrl(repoSnapshot.url);
      setPriv(repoSnapshot.privateRepository);
      setCredential(repoSnapshot.credential);
      setCache(repoSnapshot.cache);
      setTeamId(repoSnapshot.teamId);
      setError("");
      return;
    }
    if (tab === "webhooks") {
      setDraftHooks(toDraft(originalHooks));
      setError("");
      return;
    }
    if (originalMembers) setDraftMembers(toMap(originalMembers));
    setError("");
  }

  const tabDirty =
    tab === "general"
      ? repoDirty || teamDirty
      : tab === "team"
        ? membersDirty
        : tab === "webhooks"
          ? hooksDirty
          : false;

  return (
    <main className="page-container">
      <PageHeader
        title="Project settings"
        subtitle={project.name}
        actions={
          tabDirty ? (
            <>
              <Button
                type="submit"
                form="project-settings-form"
                size="icon"
                disabled={busy}
                title="Save changes"
                aria-label="Save changes"
              >
                <Save size={16} aria-hidden="true" />
              </Button>
              <Button
                type="button"
                variant="outline"
                size="icon"
                disabled={busy}
                onClick={discard}
                title="Discard changes"
                aria-label="Discard changes"
              >
                <X size={16} aria-hidden="true" />
              </Button>
            </>
          ) : null
        }
      />
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      {success && (
        <div className="success-message" role="status">
          {success}
        </div>
      )}
      <Tabs value={tab} onValueChange={switchTab} className="settings-tabs">
        <TabsList>
          <TabsTrigger value="general">General</TabsTrigger>
          <TabsTrigger value="team">{t("projects.team")}</TabsTrigger>
          <TabsTrigger value="webhooks">{t("webhooks.tab")}</TabsTrigger>
        </TabsList>
        <TabsContent
          value="general"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <Card className="project-card">
            <CardHeader>
              <CardTitle>Repository</CardTitle>
              {!repoSnapshot.privateRepository && (
                <CardAction>
                  <Button
                    type="button"
                    size="icon"
                    variant="outline"
                    title={badgeCopied ? "Copied" : "Copy badge Markdown"}
                    aria-label="Copy badge Markdown"
                    onClick={() => void copyBadge()}
                  >
                    {badgeCopied ? (
                      <Check size={14} aria-hidden="true" />
                    ) : (
                      <Copy size={14} aria-hidden="true" />
                    )}
                  </Button>
                </CardAction>
              )}
            </CardHeader>
            <CardContent>
              <form
                id="project-settings-form"
                className="settings-form"
                onSubmit={submit}
              >
                <div className="form-field">
                  <Label htmlFor="settings-name">Project name</Label>
                  <Input
                    id="settings-name"
                    required
                    maxLength="100"
                    pattern="[A-Za-z0-9][A-Za-z0-9._\-]*"
                    title="Project names use letters, digits, '.', '_' and '-', and start with a letter or digit"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="my-project"
                  />
                </div>
                <div className="form-field">
                  <Label htmlFor="settings-url">Repository URL</Label>
                  <Input
                    id="settings-url"
                    required
                    type="url"
                    value={url}
                    onChange={(e) => setUrl(e.target.value)}
                    placeholder="https://github.com/org/repo.git"
                  />
                </div>
                <div className="form-field">
                  <Label
                    htmlFor="settings-private"
                    className="whitespace-nowrap"
                  >
                    Private project
                  </Label>
                  <span className="private-choice">
                    <input
                      id="settings-private"
                      type="checkbox"
                      checked={priv}
                      disabled={!isAdmin && repoSnapshot.privateRepository}
                      onChange={(e) => setPriv(e.target.checked)}
                    />
                  </span>
                </div>
                <div className="form-field">
                  <Label htmlFor="settings-team">{t("projects.team")}</Label>
                  <select
                    id="settings-team"
                    value={teamId}
                    onChange={(e) => setTeamId(e.target.value)}
                  >
                    <option value="">{t("projects.teamNone")}</option>
                    {teams
                      .filter(
                        (teamOption) => isAdmin || teamOption.role === "LEAD",
                      )
                      .map((teamOption) => (
                        <option key={teamOption.id} value={teamOption.id}>
                          {teamOption.name}
                        </option>
                      ))}
                  </select>
                </div>
                {showCredential && (
                  <div className="form-field">
                    <Label htmlFor="settings-credential">
                      Credential reference
                    </Label>
                    <Input
                      id="settings-credential"
                      maxLength="90"
                      pattern="CISYS_GIT_[A-Z0-9_]{1,80}"
                      title="An environment variable named CISYS_GIT_..."
                      value={credential}
                      onChange={(e) => setCredential(e.target.value)}
                      placeholder="CISYS_GIT_PROJECT"
                    />
                  </div>
                )}
                <div className="form-field">
                  <Label htmlFor="settings-cache" className="whitespace-nowrap">
                    Cache
                  </Label>
                  <span className="private-choice">
                    <input
                      id="settings-cache"
                      type="checkbox"
                      checked={cache}
                      onChange={(e) => setCache(e.target.checked)}
                    />
                  </span>
                </div>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent
          value="team"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <ProjectMembers
            original={originalMembers}
            draft={draftMembers}
            users={users}
            onDraftChange={(next) => {
              setSuccess("");
              setDraftMembers(next);
            }}
            onRetry={reloadMembers}
            loadError={memberLoadError}
            user={user}
            isAdmin={isAdmin}
            busy={busy}
          />
        </TabsContent>
        <TabsContent
          value="webhooks"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <WebhooksPanel
            original={originalHooks}
            draft={draftHooks}
            deliveries={deliveries}
            secrets={secrets}
            onDraftChange={(next) => {
              setSuccess("");
              setDraftHooks(next);
            }}
            onRotate={rotateSecret}
            onRetry={() => {
              setOriginalHooks(null);
              setHookLoadError("");
            }}
            loadError={hookLoadError}
            busy={busy}
          />
        </TabsContent>
      </Tabs>
    </main>
  );
}
