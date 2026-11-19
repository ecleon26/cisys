/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import Keycloak from "keycloak-js";
import {
  FolderGit2,
  LockKeyhole,
  LockKeyholeOpen,
  Moon,
  Sun,
  LogOut,
  ChevronDown,
  CircleUserRound,
  Pencil,
  Plus,
  Trash2,
  UserRound,
} from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "./components/ui/dropdown-menu";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "./components/ui/card";
import AppFooter from "./components/layout/AppFooter";
import PageHeader from "./components/layout/PageHeader";
import ImagesPanel from "./ImagesPanel";
import SettingsPanel from "./SettingsPanel";
import UsersPanel from "./UsersPanel";
import PublicHome from "./PublicHome";
import PublicStats from "./PublicStats";
import ProjectBuilds from "./ProjectBuilds";
import ProjectBuild from "./ProjectBuild";
import ProjectSettings from "./ProjectSettings";
import {
  projectBuildFromPath,
  projectNameFromPath,
  projectPath,
  projectSettingsFromPath,
  projectSettingsPath,
} from "./project-paths";
import { buildPath } from "./build-format";
import headerLogo from "./assets/logo-header.svg";
import "./style.css";

const API = "http://localhost:8080/api";
const keycloak = new Keycloak({
  url: "http://localhost:8180",
  realm: "cisys",
  clientId: "cisys-ui",
});
// Matches the Branding entity defaults, used when the public branding cannot be loaded.
const DEFAULT_BRANDING = {
  name: "cisys",
  headerColor: "#176a4a",
  headingColor: "#176a4a",
  buttonColor: "#176a4a",
};

async function publicJson(path, opts = {}) {
  const response = await fetch(API + path);
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    const message =
      body?.error ||
      (response.status === 429
        ? "Too many requests. Try again later."
        : `Request failed (${response.status})`);
    throw Object.assign(new Error(message), {
      status: response.status,
      retryAfter: Number(response.headers.get("Retry-After")) || null,
    });
  }
  if (opts.raw) return response;
  return response.json();
}

/**
 * The caller's project role, keyed by project id. Global admins short-circuit to ADMIN
 * without calling the backend; everyone else learns their role from the team list,
 * which the backend exposes to members and admins. A failed call means no rights, not a
 * relaxed gate — the server still enforces everything.
 */
async function fetchTeamRoles(list, userInfo) {
  if (userInfo.roles.includes("admin"))
    return new Map(list.map((p) => [p.id, "ADMIN"]));
  const entries = await Promise.all(
    list.map(async (p) => {
      try {
        const members = await api(`/projects/${p.id}/team`);
        const me = Array.isArray(members)
          ? members.find(
              (m) =>
                m.username &&
                m.username.toLowerCase() === userInfo.username.toLowerCase(),
            )
          : null;
        return [p.id, me?.role ?? null];
      } catch {
        return [p.id, null];
      }
    }),
  );
  return new Map(entries);
}

async function api(path, opts = {}) {
  try {
    await keycloak.updateToken(30);
  } catch {
    // The session is gone, for example because the application was restarted.
    await keycloak.login();
    throw new Error("Your session has ended. Please sign in again.");
  }
  const response = await fetch(API + path, {
    ...opts,
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + keycloak.token,
      ...opts.headers,
    },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    if (body?.error)
      throw Object.assign(new Error(body.error), {
        status: response.status,
        retryAfter: Number(response.headers.get("Retry-After")) || null,
      });
  }
  if (!response.ok)
    throw Object.assign(
      new Error(
        response.status === 429
          ? "The build queue is full. Try again later."
          : `Request failed (${response.status}). Check the branch and project access.`,
      ),
      {
        status: response.status,
        retryAfter: Number(response.headers.get("Retry-After")) || null,
      },
    );
  if (opts.raw) return response;
  if (response.status === 204) return null;
  return response.json();
}

function App() {
  // 'loading' until Keycloak answers, then 'public' for anonymous visitors or 'app' for signed-in users.
  const [mode, setMode] = useState("loading");
  const [stats, setStats] = useState(undefined);
  const [statsError, setStatsError] = useState("");
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState(null);
  const [projects, setProjects] = useState([]);
  const [team, setTeam] = useState(() => new Map());
  const [error, setError] = useState("");
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [priv, setPriv] = useState(false);
  const [credential, setCredential] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [branding, setBranding] = useState(null);
  const [page, setPage] = useState("projects");
  const [settingsDirty, setSettingsDirty] = useState(false);
  const settingsDirtyRef = React.useRef(false);
  settingsDirtyRef.current = settingsDirty;
  // Blocks leaving the settings page while there are unsaved changes.
  const confirmLeave = () =>
    !settingsDirtyRef.current ||
    window.confirm("You have unsaved changes. Leave anyway?");
  // Set when the URL is a project page such as /cisys/, a build such as /cisys/master/build/51,
  // or the project's settings page /cisys/settings.
  const [projectName, setProjectName] = useState(
    () =>
      projectBuildFromPath(window.location.pathname)?.name ??
      projectSettingsFromPath(window.location.pathname)?.name ??
      projectNameFromPath(window.location.pathname),
  );
  const [projectBranch, setProjectBranch] = useState(
    () => projectBuildFromPath(window.location.pathname)?.branch ?? "",
  );
  const [buildId, setBuildId] = useState(
    () => projectBuildFromPath(window.location.pathname)?.buildId ?? null,
  );
  const [projectSettings, setProjectSettings] = useState(
    () => projectSettingsFromPath(window.location.pathname) !== null,
  );
  const [dark, setDark] = useState(
    () => localStorage.getItem("cisys-theme") === "dark",
  );

  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
    localStorage.setItem("cisys-theme", dark ? "dark" : "light");
  }, [dark]);

  useEffect(() => {
    if (!branding) return;
    const root = document.documentElement.style;
    root.setProperty("--header-bg", branding.headerColor);
    root.setProperty("--section-header-color", branding.headingColor);
    root.setProperty("--button-bg", branding.buttonColor);
    root.setProperty(
      "--section-header-hover",
      `color-mix(in srgb, ${branding.headingColor}, black 20%)`,
    );
    root.setProperty(
      "--button-hover",
      `color-mix(in srgb, ${branding.buttonColor}, black 20%)`,
    );
    const contrast = (hex) => {
      const rgb = [1, 3, 5]
        .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 0.179
        ? "#111111"
        : "#ffffff";
    };
    root.setProperty("--header-text", contrast(branding.headerColor));
    root.setProperty("--button-text", contrast(branding.buttonColor));
    document.title = branding.name;
  }, [branding]);

  // Loads the public statistics for the public page and the signed-in home page.
  // Numbers already on screen stay visible while refreshing; a failure replaces them with an error.
  const loadPublicStats = useCallback(async () => {
    try {
      setStats(await publicJson("/stats/public"));
      setStatsError("");
    } catch {
      setStats(null);
      setStatsError("Statistics are unavailable right now.");
    }
  }, []);

  useEffect(() => {
    const showPublic = async () => {
      setMode("public");
      const loadBranding = publicJson("/branding/public")
        .then((value) => setBranding({ ...DEFAULT_BRANDING, ...value }))
        .catch(() => setBranding(DEFAULT_BRANDING));
      await Promise.allSettled([loadBranding, loadPublicStats()]);
    };
    const showApp = async () => {
      setMode("app");
      try {
        const session = await api("/session");
        setUser(session);
        setBranding(await api("/branding"));
        const list = await api("/projects");
        setProjects(list);
        setTeam(await fetchTeamRoles(list, session));
        setReady(true);
      } catch (e) {
        setError(e.message || "Unable to load Cisys");
      }
    };
    keycloak
      .init({
        onLoad: "check-sso",
        pkceMethod: "S256",
        checkLoginIframe: false,
      })
      .then((authenticated) => (authenticated ? showApp() : showPublic()))
      // Keycloak is unreachable: still show the public page; Sign in retries the login.
      .catch(() => showPublic());
  }, [loadPublicStats]);

  useEffect(() => {
    const onPopState = () => {
      const build = projectBuildFromPath(window.location.pathname);
      const settings = projectSettingsFromPath(window.location.pathname);
      setBuildId(build ? build.buildId : null);
      setProjectBranch(build ? build.branch : "");
      setProjectSettings(settings !== null);
      setProjectName(
        settings
          ? settings.name
          : build
            ? build.name
            : projectNameFromPath(window.location.pathname),
      );
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  // Switches to an in-app page, leaving any project URL behind.
  function go(target, replace = false) {
    if (submitting || !confirmLeave()) return;
    if (window.location.pathname !== "/")
      window.history[replace ? "replaceState" : "pushState"](null, "", "/");
    setProjectName(null);
    setProjectBranch("");
    setBuildId(null);
    setProjectSettings(false);
    setSettingsDirty(false);
    setPage(target);
  }

  const goRef = React.useRef(null);
  goRef.current = go;
  // A project or build that does not exist, or that the visitor may not see, and any unknown path,
  // goes straight to the front page. The URL is replaced, so Back does not return to it.
  const showFrontPage = useCallback(() => goRef.current("home", true), []);

  useEffect(() => {
    const path = window.location.pathname;
    if (
      path !== "/" &&
      projectName === null &&
      buildId === null &&
      !projectSettings
    )
      showFrontPage();
  }, [projectName, buildId, projectSettings, showFrontPage]);

  function openProject(name, branch = "") {
    if (submitting || !confirmLeave()) return;
    window.history.pushState(null, "", projectPath(name));
    setSettingsDirty(false);
    setProjectName(name);
    setProjectBranch(branch);
    setBuildId(null);
    setProjectSettings(false);
  }

  function openBuild(name, branch, id) {
    if (!confirmLeave()) return;
    window.history.pushState(null, "", buildPath(name, branch, id));
    setSettingsDirty(false);
    setProjectName(name);
    setProjectBranch(branch);
    setBuildId(id);
    setProjectSettings(false);
  }

  function openProjectSettings(name) {
    if (submitting || !confirmLeave()) return;
    window.history.pushState(null, "", projectSettingsPath(name));
    setSettingsDirty(false);
    setProjectName(name);
    setProjectBranch("");
    setBuildId(null);
    setProjectSettings(true);
  }

  function resetProjectForm() {
    setName("");
    setUrl("");
    setPriv(false);
    setCredential("");
  }

  /** Reloads the project list together with the caller's role per project. */
  async function refreshProjects() {
    const list = await api("/projects");
    setProjects(list);
    if (user) setTeam(await fetchTeamRoles(list, user));
    return list;
  }

  async function submitProject(event) {
    event.preventDefault();
    if (submitting) return;
    setError("");
    setSubmitting(true);
    try {
      const body = { name, repositoryUrl: url, privateRepository: priv };
      // Only admins may set or clear a project credential; a lead's project has none.
      if (user.roles.includes("admin"))
        body.credentialRef =
          priv && credential.trim() ? credential.trim() : null;
      await api("/projects", {
        method: "POST",
        body: JSON.stringify(body),
      });
      await refreshProjects();
      resetProjectForm();
    } catch (e) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function deleteProject(project) {
    if (submitting) return;
    if (
      !window.confirm(
        `Delete ${project.name} and all of its builds? This cannot be undone.`,
      )
    )
      return;
    setError("");
    try {
      await api(`/projects/${project.id}`, { method: "DELETE" });
      await refreshProjects();
    } catch (e) {
      setError(e.message);
    }
  }

  // Starts a build from a project page's branch list; the branch's .cisys/workflows/ci.yml chooses the image and the steps.
  async function startBranchBuild(projectId, buildBranch) {
    await api(`/projects/${projectId}/builds`, {
      method: "POST",
      body: JSON.stringify({ branch: buildBranch }),
    });
  }

  const settingsProject =
    projectSettings && projectName !== null
      ? projects.find((p) => p.name === projectName)
      : null;
  const canEditSettings =
    settingsProject != null &&
    user != null &&
    (user.roles.includes("admin") || team.get(settingsProject.id) === "LEAD");

  // The settings page is for the project's LEADs and admins, like the pencil button.
  useEffect(() => {
    if (
      mode === "app" &&
      ready &&
      projectSettings &&
      projectName !== null &&
      !canEditSettings
    )
      showFrontPage();
  }, [
    mode,
    ready,
    projectSettings,
    projectName,
    canEditSettings,
    showFrontPage,
  ]);

  if (mode === "public")
    return (
      <PublicHome
        branding={branding || DEFAULT_BRANDING}
        stats={stats}
        statsError={statsError}
        logoUrl={API + "/branding/logo"}
        onSignIn={() => keycloak.login()}
        dark={dark}
        setDark={setDark}
      >
        {projectName !== null &&
          !projectSettings &&
          (buildId !== null ? (
            <ProjectBuild
              key={`${projectName}-${projectBranch}-${buildId}`}
              name={projectName}
              branch={projectBranch}
              buildId={buildId}
              load={publicJson}
              onBack={() => openProject(projectName, projectBranch)}
              onOpenBuild={openBuild}
              onNotFound={showFrontPage}
            />
          ) : (
            <ProjectBuilds
              key={projectName}
              name={projectName}
              initialBranch={projectBranch}
              load={publicJson}
              onOpenBuild={openBuild}
              onNotFound={showFrontPage}
            />
          ))}
      </PublicHome>
    );
  if (!ready)
    return (
      <main className="loading-state" role={error ? "alert" : "status"}>
        {error || "Connecting to Cisys…"}
      </main>
    );
  const canCreate = user.roles.includes("lead") || user.roles.includes("admin");
  const canManageUsers = canCreate;
  // Action visibility follows each project's team, as reported by the backend:
  // global admins do anything, project LEADs edit (they cannot delete), project
  // MEMBERs only view and build. project.owned is not consulted.
  const isAdmin = user.roles.includes("admin");
  const canDelete = () => isAdmin;
  const canEdit = (project) => isAdmin || team.get(project.id) === "LEAD";
  const viewedProject =
    projectName !== null ? projects.find((p) => p.name === projectName) : null;
  const canStartBuilds =
    viewedProject != null && (isAdmin || team.get(viewedProject.id) != null);
  // Only admins can set a project credential, and only a private project can have one.
  const showCredential = user.roles.includes("admin") && priv;
  const role = user.roles.includes("admin")
    ? "Admin"
    : user.roles.includes("lead")
      ? "Lead"
      : "Member";

  return (
    <div
      className="app-shell"
      style={
        branding.background
          ? {
              backgroundImage: `linear-gradient(rgba(0,0,0,.08), rgba(0,0,0,.08)), url(${branding.background})`,
              backgroundSize: "cover",
              backgroundAttachment: "fixed",
            }
          : undefined
      }
    >
      <header className="app-header">
        <div className="header-main">
          <a
            className="brand"
            href="/"
            aria-label={`${branding.name} home`}
            onClick={(event) => {
              event.preventDefault();
              go("home");
              void loadPublicStats();
            }}
          >
            <img
              src={branding.logo || headerLogo}
              alt=""
              className={
                branding.logo ? "brand-logo" : "brand-logo brand-logo-default"
              }
            />
            <span>{branding.name}</span>
          </a>
          <nav aria-label="Primary">
            <button
              type="button"
              className="nav-link"
              aria-current={
                projectName === null && page === "projects" ? "page" : undefined
              }
              onClick={() => go("projects")}
            >
              Projects
            </button>
            {user.roles.includes("admin") && (
              <button
                type="button"
                className="nav-link"
                aria-current={
                  projectName === null && page === "images" ? "page" : undefined
                }
                onClick={() => go("images")}
              >
                Images
              </button>
            )}
            {canManageUsers && (
              <button
                type="button"
                className="nav-link"
                aria-current={
                  projectName === null && page === "users" ? "page" : undefined
                }
                onClick={() => go("users")}
              >
                Users
              </button>
            )}
            {user.roles.includes("admin") && (
              <button
                type="button"
                className="nav-link"
                aria-current={
                  projectName === null && page === "settings"
                    ? "page"
                    : undefined
                }
                onClick={() => go("settings")}
              >
                Settings
              </button>
            )}
          </nav>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="theme-toggle"
            onClick={() => setDark(!dark)}
            aria-label="Toggle dark mode"
            aria-pressed={dark}
            title="Toggle dark mode"
          >
            <Sun size={15} aria-hidden="true" />
            <Moon size={15} aria-hidden="true" />
            <span className="theme-knob" />
          </button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="user-menu-trigger"
                aria-label="Account menu"
                title="Account menu"
              >
                <CircleUserRound size={22} aria-hidden="true" />
                <ChevronDown size={14} aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => go("profile")}>
                <UserRound aria-hidden="true" /> Profile
              </DropdownMenuItem>
              <DropdownMenuItem
                onSelect={() =>
                  keycloak.logout({ redirectUri: window.location.origin + "/" })
                }
              >
                <LogOut aria-hidden="true" /> Sign out
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      {projectName !== null ? (
        projectSettings ? (
          canEditSettings ? (
            <ProjectSettings
              key={settingsProject.id}
              project={settingsProject}
              api={api}
              isAdmin={user.roles.includes("admin")}
              user={user}
              onDirtyChange={setSettingsDirty}
              onSaved={async (saved) => {
                await refreshProjects();
                openProject(saved.name);
              }}
            />
          ) : null
        ) : buildId !== null ? (
          <ProjectBuild
            key={`${projectName}-${projectBranch}-${buildId}`}
            name={projectName}
            branch={projectBranch}
            buildId={buildId}
            load={api}
            onBack={() => openProject(projectName, projectBranch)}
            onOpenBuild={openBuild}
            onNotFound={showFrontPage}
          />
        ) : (
          <ProjectBuilds
            key={projectName}
            name={projectName}
            initialBranch={projectBranch}
            load={api}
            onOpenBuild={openBuild}
            onBuild={canStartBuilds ? startBranchBuild : undefined}
            onNotFound={showFrontPage}
          />
        )
      ) : page === "home" ? (
        <main id="home" className="page-container">
          <PublicStats stats={stats} statsError={statsError} />
        </main>
      ) : page === "profile" ? (
        <main id="profile" className="page-container">
          <PageHeader title="Profile" />
          <Card className="project-card">
            <CardHeader>
              <CardTitle>{role}</CardTitle>
              <CardDescription>Signed in as {user.username}</CardDescription>
            </CardHeader>
          </Card>
        </main>
      ) : page === "images" && user.roles.includes("admin") ? (
        <ImagesPanel api={api} />
      ) : page === "users" && canManageUsers ? (
        <UsersPanel api={api} user={user} />
      ) : page === "settings" && user.roles.includes("admin") ? (
        <SettingsPanel
          api={api}
          branding={branding}
          saveBranding={async (value) => {
            const saved = await api("/branding", {
              method: "PUT",
              body: JSON.stringify(value),
            });
            setBranding(saved);
            return saved;
          }}
        />
      ) : (
        <main id="projects" className="page-container">
          <PageHeader
            title="Projects"
            actions={
              <Badge variant="outline">
                {projects.length}{" "}
                {projects.length === 1 ? "project" : "projects"}
              </Badge>
            }
          />
          {error && (
            <div className="error-message" role="alert">
              {error}
            </div>
          )}
          {canCreate && (
            <Card className="project-card">
              <CardHeader>
                <CardTitle>Add a project</CardTitle>
              </CardHeader>
              <CardContent>
                <form
                  className={`project-grid project-add${showCredential ? " has-credential" : ""}`}
                  onSubmit={submitProject}
                >
                  <div className="form-field">
                    <Label htmlFor="project-name">Project name</Label>
                    <Input
                      id="project-name"
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
                    <Label htmlFor="repository-url">Repository URL</Label>
                    <Input
                      id="repository-url"
                      required
                      type="url"
                      value={url}
                      onChange={(e) => setUrl(e.target.value)}
                      placeholder="https://github.com/org/repo.git"
                    />
                  </div>
                  <div className="form-field">
                    <Label
                      htmlFor="project-private"
                      className="whitespace-nowrap"
                    >
                      Private project
                    </Label>
                    <span className="private-choice">
                      <input
                        id="project-private"
                        type="checkbox"
                        checked={priv}
                        onChange={(e) => setPriv(e.target.checked)}
                      />
                    </span>
                  </div>
                  {showCredential && (
                    <div className="form-field">
                      <Label htmlFor="repository-credential">
                        Credential reference
                      </Label>
                      <Input
                        id="repository-credential"
                        maxLength="90"
                        pattern="CISYS_GIT_[A-Z0-9_]{1,80}"
                        title="An environment variable named CISYS_GIT_..."
                        value={credential}
                        onChange={(e) => setCredential(e.target.value)}
                        placeholder="CISYS_GIT_PROJECT"
                      />
                    </div>
                  )}
                  <Button
                    type="submit"
                    size="icon"
                    className="h-10 w-10 justify-self-end"
                    disabled={submitting}
                    title="Add project"
                    aria-label="Add project"
                  >
                    <Plus size={16} aria-hidden="true" />
                  </Button>
                </form>
              </CardContent>
            </Card>
          )}
          <Card className="project-card">
            <CardHeader>
              <CardTitle>Repositories</CardTitle>
            </CardHeader>
            <CardContent>
              {projects.length === 0 ? (
                <div className="empty-state">
                  <FolderGit2 size={28} aria-hidden="true" />
                  <strong>No projects yet</strong>
                  {!canCreate && <p>Ask a lead or admin to add a project.</p>}
                </div>
              ) : (
                projects.map((project) => (
                  <div className="project-grid project-row" key={project.id}>
                    <strong className="project-name">
                      <a
                        href={projectPath(project.name)}
                        onClick={(event) => {
                          event.preventDefault();
                          openProject(project.name);
                        }}
                      >
                        {project.name}
                      </a>
                    </strong>
                    <p className="project-url">{project.repositoryUrl}</p>
                    <Badge variant="outline" className="justify-self-start">
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
                    <div className="project-actions">
                      {canEdit(project) && (
                        <Button
                          type="button"
                          size="icon"
                          disabled={submitting}
                          title="Edit project"
                          aria-label={`Edit project ${project.name}`}
                          onClick={() => openProjectSettings(project.name)}
                        >
                          <Pencil size={16} aria-hidden="true" />
                        </Button>
                      )}
                      {canDelete() && (
                        <Button
                          type="button"
                          size="icon"
                          disabled={submitting}
                          title="Delete project"
                          aria-label={`Delete project ${project.name}`}
                          onClick={() => void deleteProject(project)}
                        >
                          <Trash2 size={16} aria-hidden="true" />
                        </Button>
                      )}
                    </div>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </main>
      )}
      <AppFooter />
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);
