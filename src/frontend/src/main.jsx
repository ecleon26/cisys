/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  createBrowserRouter,
  RouterProvider,
  useBlocker,
  useLocation,
  useNavigate,
} from "react-router-dom";
import Keycloak from "keycloak-js";
import { useTranslation } from "react-i18next";
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
import AuditPanel from "./AuditPanel";
import BuildStatistics from "./BuildStatistics";
import PublicHome from "./PublicHome";
import PublicStats from "./PublicStats";
import ProjectBuilds from "./ProjectBuilds";
import ProjectBuild from "./ProjectBuild";
import ProjectSettings from "./ProjectSettings";
import ManualPage from "./ManualPage";
import ManualButton from "./ManualButton";
import ArticlesButton from "./ArticlesButton";
import ArticlesPage from "./ArticlesPage";
import { chapterBySlug, chapters } from "./manual";
import {
  articlesPath,
  manualPath,
  pagePath,
  projectPath,
  projectSettingsPath,
  readTabFromSearch,
  routeFromPath,
  withTab,
} from "./project-paths";
import { buildPath } from "./build-format";
import { resolveConfig } from "./app-config";
import headerLogo from "./assets/logo-header.svg";
import i18n from "./i18n";
import "./style.css";

const { apiUrl: API, keycloakUrl, realm, clientId } = resolveConfig();
const keycloak = new Keycloak({
  url: keycloakUrl,
  realm,
  clientId,
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
        ? i18n.t("errors.tooManyRequests")
        : i18n.t("errors.requestFailed", { status: response.status }));
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
    throw new Error(i18n.t("errors.sessionEnded"));
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
          ? i18n.t("errors.queueFull")
          : i18n.t("errors.requestFailedAccess", { status: response.status }),
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

function AppPage() {
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
  const [settingsDirty, setSettingsDirty] = useState(false);
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const route = useMemo(
    () => routeFromPath(location.pathname),
    [location.pathname],
  );
  const page = route?.page ?? null;
  const manual = route?.manual ?? null;
  const articles = route?.articles ?? null;
  const projectName = route?.project ?? null;
  // A project page's branch is not in its URL; Back from a build passes it along.
  const projectBranch = route?.branch ?? location.state?.branch ?? "";
  const buildId = route?.buildId ?? null;
  const projectSettings = Boolean(route?.settings);
  const projectTab = readTabFromSearch(location.search);
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
      setStatsError(i18n.t("app.statsUnavailable"));
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
        setError(e.message || i18n.t("app.loadFailed"));
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

  // Leaving a page, Back included, waits for an added project and asks about unsaved settings.
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      currentLocation.pathname !== nextLocation.pathname &&
      (submitting || settingsDirty),
  );
  useEffect(() => {
    if (blocker.state !== "blocked") return;
    if (!submitting && window.confirm(i18n.t("app.unsavedChanges")))
      blocker.proceed();
    else blocker.reset();
  }, [blocker, submitting]);

  const go = (target, replace = false) =>
    navigate(pagePath(target), { replace });
  const openManual = (slug) => navigate(manualPath(slug));
  const openArticles = (next = {}) =>
    navigate(articlesPath({ id: next.id ?? null, edit: Boolean(next.edit) }));
  const openProject = (
    name,
    branch = "",
    tab = "builds",
    search = "",
    hash = "",
  ) => navigate(projectPath(name, tab, search, hash), { state: { branch } });
  // A build keeps the project's query and hash, so Back returns to the same tab.
  const openBuild = (name, branch, id) =>
    navigate(
      buildPath(name, branch, id) +
        withTab(location.search, projectTab) +
        location.hash,
    );
  const openProjectSettings = (name) => navigate(projectSettingsPath(name));

  // A project or build that does not exist, or that the visitor may not see, and any unknown path,
  // goes straight to the front page. The URL is replaced, so Back does not return to it.
  const showFrontPage = useCallback(
    () => navigate(mode === "app" ? pagePath("home") : "/", { replace: true }),
    [navigate, mode],
  );

  useEffect(() => {
    if (mode === "loading") return;
    if (
      route === null ||
      (mode === "public" && page !== null && page !== "projects")
    )
      showFrontPage();
  }, [mode, route, page, showFrontPage]);

  // /manual and unknown chapters show the first chapter.
  useEffect(() => {
    if (manual && !chapterBySlug(manual.slug) && chapters.length > 0)
      navigate(manualPath(chapters[0].slug), { replace: true });
  }, [manual, navigate]);

  function changeProjectTab(tab) {
    if (tab === projectTab) return;
    navigate(
      location.pathname + withTab(location.search, tab) + location.hash,
      {
        replace: true,
        state: location.state,
      },
    );
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
    if (!window.confirm(t("projects.deleteConfirm", { name: project.name })))
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

  // A project's settings page is for its LEADs and admins, like the pencil button; the top-level
  // pages follow the navigation links.
  useEffect(() => {
    if (mode !== "app" || !ready) return;
    const admin = user.roles.includes("admin");
    const lead = admin || user.roles.includes("lead");
    if (
      (projectSettings && projectName !== null && !canEditSettings) ||
      ((page === "images" || page === "settings" || page === "audit") &&
        !admin) ||
      ((page === "users" || page === "reports") && !lead)
    )
      showFrontPage();
  }, [
    mode,
    ready,
    user,
    page,
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
        onOpenManual={() => openManual(manual?.slug ?? chapters[0]?.slug)}
        manualActive={manual !== null}
        onOpenArticles={() => openArticles()}
        articlesActive={articles !== null}
      >
        {manual !== null ? (
          <ManualPage slug={manual.slug} onOpen={openManual} />
        ) : articles !== null ? (
          <ArticlesPage
            route={articles}
            load={publicJson}
            onOpen={openArticles}
          />
        ) : (
          projectName !== null &&
          !projectSettings &&
          (buildId !== null ? (
            <ProjectBuild
              key={`${projectName}-${projectBranch}-${buildId}`}
              name={projectName}
              branch={projectBranch}
              buildId={buildId}
              load={publicJson}
              onBack={() =>
                openProject(
                  projectName,
                  projectBranch,
                  projectTab,
                  location.search,
                  location.hash,
                )
              }
              onOpenBuild={openBuild}
              onNotFound={showFrontPage}
            />
          ) : (
            <ProjectBuilds
              key={projectName}
              name={projectName}
              initialBranch={projectBranch}
              tab={projectTab}
              onTabChange={changeProjectTab}
              load={publicJson}
              apiUrl={API}
              onOpenBuild={openBuild}
              onNotFound={showFrontPage}
            />
          ))
        )}
      </PublicHome>
    );
  if (!ready)
    return (
      <main className="loading-state" role={error ? "alert" : "status"}>
        {error || t("app.connecting")}
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
  const onPage = (target) => page === target;
  const role = user.roles.includes("admin")
    ? t("roles.admin")
    : user.roles.includes("lead")
      ? t("roles.lead")
      : t("roles.member");

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
            href={pagePath("home")}
            aria-label={t("app.home", { name: branding.name })}
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
          <nav aria-label={t("nav.primary")}>
            <button
              type="button"
              className="nav-link"
              aria-current={onPage("projects") ? "page" : undefined}
              onClick={() => go("projects")}
            >
              {t("nav.projects")}
            </button>
            {user.roles.includes("admin") && (
              <button
                type="button"
                className="nav-link"
                aria-current={onPage("images") ? "page" : undefined}
                onClick={() => go("images")}
              >
                {t("nav.images")}
              </button>
            )}
            {canManageUsers && (
              <button
                type="button"
                className="nav-link"
                aria-current={onPage("users") ? "page" : undefined}
                onClick={() => go("users")}
              >
                {t("nav.users")}
              </button>
            )}
            {user.roles.includes("admin") && (
              <button
                type="button"
                className="nav-link"
                aria-current={onPage("audit") ? "page" : undefined}
                onClick={() => go("audit")}
              >
                {t("nav.audit")}
              </button>
            )}
            {canCreate && (
              <button
                type="button"
                className="nav-link"
                aria-current={onPage("reports") ? "page" : undefined}
                onClick={() => go("reports")}
              >
                {t("nav.reports")}
              </button>
            )}
            {user.roles.includes("admin") && (
              <button
                type="button"
                className="nav-link"
                aria-current={onPage("settings") ? "page" : undefined}
                onClick={() => go("settings")}
              >
                {t("nav.settings")}
              </button>
            )}
          </nav>
        </div>
        <div className="header-actions">
          <ArticlesButton
            active={articles !== null}
            onOpen={() => openArticles()}
          />
          <ManualButton
            active={manual !== null}
            onOpen={() => openManual(chapters[0]?.slug)}
          />
          <button
            type="button"
            className="theme-toggle"
            onClick={() => setDark(!dark)}
            aria-label={t("nav.toggleDarkMode")}
            aria-pressed={dark}
            title={t("nav.toggleDarkMode")}
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
                aria-label={t("nav.accountMenu")}
                title={t("nav.accountMenu")}
              >
                <CircleUserRound size={22} aria-hidden="true" />
                <ChevronDown size={14} aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => go("profile")}>
                <UserRound aria-hidden="true" /> {t("nav.profile")}
              </DropdownMenuItem>
              <DropdownMenuItem
                onSelect={() =>
                  keycloak.logout({ redirectUri: window.location.origin + "/" })
                }
              >
                <LogOut aria-hidden="true" /> {t("nav.signOut")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      {manual !== null ? (
        <ManualPage slug={manual.slug} onOpen={openManual} />
      ) : articles !== null ? (
        <ArticlesPage route={articles} load={api} onOpen={openArticles} />
      ) : projectName !== null ? (
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
            onBack={() =>
              openProject(
                projectName,
                projectBranch,
                projectTab,
                location.search,
                location.hash,
              )
            }
            onOpenBuild={openBuild}
            onNotFound={showFrontPage}
          />
        ) : (
          <ProjectBuilds
            key={projectName}
            name={projectName}
            initialBranch={projectBranch}
            tab={projectTab}
            onTabChange={changeProjectTab}
            load={api}
            apiUrl={API}
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
          <PageHeader title={t("profile.title")} />
          <Card className="project-card">
            <CardHeader>
              <CardTitle>{role}</CardTitle>
              <CardDescription>
                {t("profile.signedInAs", { username: user.username })}
              </CardDescription>
            </CardHeader>
          </Card>
        </main>
      ) : page === "images" && user.roles.includes("admin") ? (
        <ImagesPanel api={api} />
      ) : page === "users" && canManageUsers ? (
        <UsersPanel api={api} user={user} />
      ) : page === "reports" && canCreate ? (
        <BuildStatistics
          load={api}
          onOpenBuild={openBuild}
          onOpenProject={openProject}
        />
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
      ) : page === "audit" && user.roles.includes("admin") ? (
        <AuditPanel api={api} />
      ) : (
        <main id="projects" className="page-container">
          <PageHeader
            title={t("projects.title")}
            actions={
              <Badge variant="outline">
                {t("projects.count", { count: projects.length })}
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
                <CardTitle>{t("projects.add")}</CardTitle>
              </CardHeader>
              <CardContent>
                <form
                  className={`project-grid project-add${showCredential ? " has-credential" : ""}`}
                  onSubmit={submitProject}
                >
                  <div className="form-field">
                    <Label htmlFor="project-name">{t("projects.name")}</Label>
                    <Input
                      id="project-name"
                      required
                      maxLength="100"
                      pattern="[A-Za-z0-9][A-Za-z0-9._\-]*"
                      title={t("projects.nameRule")}
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      placeholder="my-project"
                    />
                  </div>
                  <div className="form-field">
                    <Label htmlFor="repository-url">
                      {t("projects.repositoryUrl")}
                    </Label>
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
                      {t("projects.private")}
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
                        {t("projects.credential")}
                      </Label>
                      <Input
                        id="repository-credential"
                        maxLength="90"
                        pattern="CISYS_GIT_[A-Z0-9_]{1,80}"
                        title={t("projects.credentialRule")}
                        value={credential}
                        onChange={(e) => setCredential(e.target.value)}
                        placeholder="CISYS_GIT_PROJECT"
                      />
                    </div>
                  )}
                  <Button
                    type="submit"
                    size="icon"
                    className="justify-self-end"
                    disabled={submitting}
                    title={t("projects.addButton")}
                    aria-label={t("projects.addButton")}
                  >
                    <Plus size={16} aria-hidden="true" />
                  </Button>
                </form>
              </CardContent>
            </Card>
          )}
          <Card className="project-card">
            <CardHeader>
              <CardTitle>{t("projects.repositories")}</CardTitle>
            </CardHeader>
            <CardContent>
              {projects.length === 0 ? (
                <div className="empty-state">
                  <FolderGit2 size={28} aria-hidden="true" />
                  <strong>{t("projects.empty")}</strong>
                  {!canCreate && <p>{t("projects.emptyHint")}</p>}
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
                      {project.privateRepository
                        ? t("projects.privateBadge")
                        : t("projects.publicBadge")}
                    </Badge>
                    <div className="project-actions">
                      {canEdit(project) && (
                        <Button
                          type="button"
                          size="icon"
                          disabled={submitting}
                          title={t("projects.edit")}
                          aria-label={t("projects.editNamed", {
                            name: project.name,
                          })}
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
                          title={t("projects.delete")}
                          aria-label={t("projects.deleteNamed", {
                            name: project.name,
                          })}
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

// App reads the page from the URL itself; the router supplies navigation and the leave blocker.
export function App() {
  const [router] = useState(() =>
    createBrowserRouter([{ path: "*", element: <AppPage /> }]),
  );
  return <RouterProvider router={router} />;
}

const root = document.getElementById("root");
if (root) createRoot(root).render(<App />);
