/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Check,
  ChevronLeft,
  ChevronRight,
  KeyRound,
  Pencil,
  Plus,
  Trash2,
  Users,
  X,
} from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";
import PageHeader from "./components/layout/PageHeader";
import ProjectPicker from "./components/users/ProjectPicker";

const ROLES = ["member", "lead", "admin"];
const PAGE_SIZE = 50;
const EMPTY = {
  username: "",
  email: "",
  firstName: "",
  lastName: "",
  password: "",
  role: "member",
  projectIds: [],
};

export default function UsersPanel({ api, user, tabs }) {
  const admin = user.roles.includes("admin");
  const [page, setPage] = useState(null);
  const [projects, setProjects] = useState([]);
  const [search, setSearch] = useState("");
  const [role, setRole] = useState("");
  const [project, setProject] = useState("");
  const [first, setFirst] = useState(0);
  const [draft, setDraft] = useState(EMPTY);
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const listRequest = useRef(0);

  const load = useCallback(async () => {
    const request = ++listRequest.current;
    const query = new URLSearchParams({ first, max: PAGE_SIZE });
    if (search.trim()) query.set("search", search.trim());
    if (role) query.set("role", role);
    if (project) query.set("project", project);
    const data = await api(`/users?${query}`);
    // A slow, older response must not overwrite a newer filter or page.
    if (request !== listRequest.current) return;
    const lastPage = Math.max(
      0,
      Math.floor((data.total - 1) / PAGE_SIZE) * PAGE_SIZE,
    );
    if (data.users.length === 0 && lastPage < first) {
      setFirst(lastPage);
      return;
    }
    setPage(data);
    setError("");
  }, [api, first, search, role, project]);

  useEffect(() => {
    api("/users/projects")
      .then(setProjects)
      .catch((e) => setError(e.message));
  }, [api]);

  useEffect(() => {
    let active = true;
    const timer = setTimeout(
      () => load().catch((e) => active && setError(e.message)),
      200,
    );
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [load]);

  async function run(action) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  function add(event) {
    event.preventDefault();
    void run(async () => {
      await api("/users", {
        method: "POST",
        body: JSON.stringify(
          admin ? { ...draft, projectIds: [] } : { ...draft, role: "member" },
        ),
      });
      setDraft(EMPTY);
      await load();
    });
  }

  function save(event) {
    event.preventDefault();
    const body = admin ? editing : { projectIds: editing.projectIds };
    void run(async () => {
      await api(`/users/${editing.id}`, {
        method: "PUT",
        body: JSON.stringify(body),
      });
      setEditing(null);
      await load();
    });
  }

  function resetPassword(u) {
    const password = window.prompt(`Temporary password for ${u.username}`);
    if (!password) return;
    void run(() =>
      api(`/users/${u.id}/password`, {
        method: "PUT",
        body: JSON.stringify({ password }),
      }),
    );
  }

  function remove(u) {
    if (!window.confirm(`Delete ${u.username}? This cannot be undone.`)) return;
    void run(async () => {
      await api(`/users/${u.id}`, { method: "DELETE" });
      await load();
    });
  }

  function filter(setter) {
    return (e) => {
      setter(e.target.value);
      setFirst(0);
    };
  }

  const total = page?.total ?? 0;
  const canAdd = admin || projects.length > 0;

  return (
    <main id="users" className="page-container">
      <PageHeader
        title="Users"
        actions={
          <Badge variant="outline">
            {total} {total === 1 ? "user" : "users"}
          </Badge>
        }
      />
      {tabs}
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      {canAdd && (
        <Card className="project-card">
          <CardHeader>
            <CardTitle>Add a user</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="user-add" onSubmit={add}>
              <div className="form-field">
                <Label htmlFor="user-username">User name</Label>
                <Input
                  id="user-username"
                  required
                  maxLength="100"
                  autoComplete="off"
                  spellCheck={false}
                  value={draft.username}
                  onChange={(e) =>
                    setDraft({ ...draft, username: e.target.value })
                  }
                />
              </div>
              <div className="form-field">
                <Label htmlFor="user-first">First name</Label>
                <Input
                  id="user-first"
                  maxLength="100"
                  value={draft.firstName}
                  onChange={(e) =>
                    setDraft({ ...draft, firstName: e.target.value })
                  }
                />
              </div>
              <div className="form-field">
                <Label htmlFor="user-last">Last name</Label>
                <Input
                  id="user-last"
                  maxLength="100"
                  value={draft.lastName}
                  onChange={(e) =>
                    setDraft({ ...draft, lastName: e.target.value })
                  }
                />
              </div>
              <div className="form-field">
                <Label htmlFor="user-email">Email</Label>
                <Input
                  id="user-email"
                  type="email"
                  maxLength="254"
                  value={draft.email}
                  onChange={(e) =>
                    setDraft({ ...draft, email: e.target.value })
                  }
                />
              </div>
              <div className="form-field">
                <Label htmlFor="user-password">Password</Label>
                <Input
                  id="user-password"
                  type="password"
                  required
                  maxLength="255"
                  autoComplete="new-password"
                  value={draft.password}
                  onChange={(e) =>
                    setDraft({ ...draft, password: e.target.value })
                  }
                />
              </div>
              {admin && (
                <div className="form-field">
                  <Label htmlFor="user-role">Role</Label>
                  <Select
                    value={draft.role}
                    onValueChange={(role) => setDraft({ ...draft, role })}
                  >
                    <SelectTrigger id="user-role" className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {ROLES.map((r) => (
                        <SelectItem key={r} value={r}>
                          {r}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
              {!admin && (
                <div className="form-field">
                  <Label>Projects</Label>
                  <ProjectPicker
                    projects={projects}
                    value={draft.projectIds}
                    idPrefix="new-user-project"
                    label="Projects for the new user"
                    onChange={(projectIds) =>
                      setDraft({ ...draft, projectIds })
                    }
                  />
                </div>
              )}
              <Button
                type="submit"
                size="icon"
                className="justify-self-end self-end"
                disabled={busy || (!admin && draft.projectIds.length === 0)}
                title="Add user"
                aria-label="Add user"
              >
                <Plus size={16} aria-hidden="true" />
              </Button>
            </form>
          </CardContent>
        </Card>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Users</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="user-filters">
            <Input
              aria-label="Search users"
              type="search"
              placeholder="Search"
              value={search}
              onChange={filter(setSearch)}
            />
            {admin && (
              <Select
                value={role || "all"}
                onValueChange={(value) => {
                  setRole(value === "all" ? "" : value);
                  setFirst(0);
                }}
              >
                <SelectTrigger aria-label="Role" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All roles</SelectItem>
                  {ROLES.map((r) => (
                    <SelectItem key={r} value={r}>
                      {r}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
            <Select
              value={project || "all"}
              onValueChange={(value) => {
                setProject(value === "all" ? "" : value);
                setFirst(0);
              }}
            >
              <SelectTrigger aria-label="Project" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">
                  {admin ? "All projects" : "My projects"}
                </SelectItem>
                {projects.map((p) => (
                  <SelectItem key={p.id} value={String(p.id)}>
                    {p.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {page === null ? (
            !error && <p role="status">Loading users…</p>
          ) : page.users.length === 0 ? (
            <div className="empty-state">
              <Users size={28} aria-hidden="true" />
              <strong>No users</strong>
            </div>
          ) : (
            page.users.map((u) =>
              editing?.id === u.id ? (
                <form
                  className="user-row user-edit-row"
                  key={u.id}
                  onSubmit={save}
                >
                  <div className="user-edit w-full">
                    <div className="form-field">
                      <Label htmlFor={`user-${u.id}-username`}>User name</Label>
                      <Input
                        id={`user-${u.id}-username`}
                        value={u.username}
                        readOnly
                        disabled
                      />
                    </div>
                    {admin && (
                      <>
                        <div className="form-field">
                          <Label htmlFor={`user-${u.id}-email`}>Email</Label>
                          <Input
                            id={`user-${u.id}-email`}
                            type="email"
                            maxLength="254"
                            value={editing.email}
                            onChange={(e) =>
                              setEditing({ ...editing, email: e.target.value })
                            }
                          />
                        </div>
                        <div className="form-field">
                          <Label htmlFor={`user-${u.id}-first`}>
                            First name
                          </Label>
                          <Input
                            id={`user-${u.id}-first`}
                            maxLength="100"
                            value={editing.firstName}
                            onChange={(e) =>
                              setEditing({
                                ...editing,
                                firstName: e.target.value,
                              })
                            }
                          />
                        </div>
                        <div className="form-field">
                          <Label htmlFor={`user-${u.id}-last`}>Last name</Label>
                          <Input
                            id={`user-${u.id}-last`}
                            maxLength="100"
                            value={editing.lastName}
                            onChange={(e) =>
                              setEditing({
                                ...editing,
                                lastName: e.target.value,
                              })
                            }
                          />
                        </div>
                        <div className="form-field">
                          <Label htmlFor={`user-${u.id}-role`}>Role</Label>
                          <Select
                            value={editing.role || "none"}
                            onValueChange={(value) =>
                              setEditing({
                                ...editing,
                                role: value === "none" ? "" : value,
                              })
                            }
                          >
                            <SelectTrigger
                              id={`user-${u.id}-role`}
                              aria-label={`Role for ${u.username}`}
                              className="w-full"
                            >
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {editing.role === "" && (
                                <SelectItem value="none">—</SelectItem>
                              )}
                              {ROLES.map((r) => (
                                <SelectItem key={r} value={r}>
                                  {r}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                        <label className="user-enabled">
                          <input
                            type="checkbox"
                            checked={editing.enabled}
                            onChange={(e) =>
                              setEditing({
                                ...editing,
                                enabled: e.target.checked,
                              })
                            }
                          />
                          Enabled
                        </label>
                      </>
                    )}
                    <div className="form-field">
                      <Label>Projects</Label>
                      <ProjectPicker
                        projects={projects}
                        value={editing.projectIds}
                        idPrefix={`user-${u.id}-project`}
                        label={`Projects for ${u.username}`}
                        onChange={(projectIds) =>
                          setEditing({ ...editing, projectIds })
                        }
                      />
                    </div>
                  </div>
                  <div className="user-actions">
                    <Button
                      type="submit"
                      size="icon"
                      disabled={busy}
                      title="Save user"
                      aria-label={`Save ${u.username}`}
                    >
                      <Check size={16} aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      size="icon"
                      variant="outline"
                      title="Cancel"
                      aria-label="Cancel"
                      onClick={() => setEditing(null)}
                    >
                      <X size={16} aria-hidden="true" />
                    </Button>
                  </div>
                </form>
              ) : (
                <div className="user-row" key={u.id}>
                  <div className="project-name">
                    <strong>{u.username}</strong>
                    {!u.enabled && (
                      <Badge variant="outline" className="ms-2">
                        Disabled
                      </Badge>
                    )}
                  </div>
                  <p className="project-url">
                    {[u.firstName, u.lastName].filter(Boolean).join(" ")}
                    {u.email && <span className="user-email">{u.email}</span>}
                  </p>
                  <div className="user-tags">
                    {u.role && <Badge variant="outline">{u.role}</Badge>}
                    {u.projects.slice(0, 4).map((p) => (
                      <Badge key={p.id} variant="secondary">
                        {p.name}
                      </Badge>
                    ))}
                    {u.projects.length > 4 && (
                      <Badge
                        variant="outline"
                        title={u.projects
                          .slice(4)
                          .map((p) => p.name)
                          .join(", ")}
                      >
                        +{u.projects.length - 4} more
                      </Badge>
                    )}
                  </div>
                  <div className="user-actions">
                    {u.manageable ? (
                      <Button
                        type="button"
                        size="icon"
                        variant="outline"
                        disabled={busy}
                        title="Edit user"
                        aria-label={`Edit ${u.username}`}
                        onClick={() =>
                          setEditing({
                            id: u.id,
                            email: u.email ?? "",
                            firstName: u.firstName ?? "",
                            lastName: u.lastName ?? "",
                            role: u.role ?? "",
                            enabled: u.enabled,
                            projectIds: u.projects.map((p) => p.id),
                          })
                        }
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </Button>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                    {u.manageable && (admin || u.projects.length > 0) ? (
                      <Button
                        type="button"
                        size="icon"
                        variant="outline"
                        disabled={busy}
                        title="Set temporary password"
                        aria-label={`Set temporary password for ${u.username}`}
                        onClick={() => resetPassword(u)}
                      >
                        <KeyRound size={16} aria-hidden="true" />
                      </Button>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                    {admin && u.id !== user.subject ? (
                      <Button
                        type="button"
                        size="icon"
                        disabled={busy}
                        title="Delete user"
                        aria-label={`Delete ${u.username}`}
                        onClick={() => remove(u)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                  </div>
                </div>
              ),
            )
          )}
          {(total > PAGE_SIZE || first > 0) && (
            <div className="user-paging">
              <Button
                type="button"
                size="icon"
                variant="outline"
                disabled={first === 0}
                title="Previous page"
                aria-label="Previous page"
                onClick={() => setFirst(Math.max(0, first - PAGE_SIZE))}
              >
                <ChevronLeft size={16} aria-hidden="true" />
              </Button>
              <span>
                {total === 0 ? 0 : Math.min(first + 1, total)}–
                {Math.min(first + PAGE_SIZE, total)} of {total}
              </span>
              <Button
                type="button"
                size="icon"
                variant="outline"
                disabled={first + PAGE_SIZE >= total}
                title="Next page"
                aria-label="Next page"
                onClick={() => setFirst(first + PAGE_SIZE)}
              >
                <ChevronRight size={16} aria-hidden="true" />
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
