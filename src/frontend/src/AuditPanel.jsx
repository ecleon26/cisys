/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, ScrollText, X } from "lucide-react";
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
import { formatDateTime } from "./i18n";

const PAGE_SIZES = [25, 50, 100];
const OUTCOMES = ["SUCCESS", "DENIED", "FAILURE"];
const TARGETS = [
  "PROJECT",
  "PROJECT_MEMBER",
  "USER",
  "SETTINGS",
  "BRANDING",
  "IMAGE",
  "NETWORK_HOST",
  "NETWORK_TEMPLATE",
  "ARTICLE",
  "BUILD",
];

function outcomeVariant(outcome) {
  if (outcome === "DENIED") return "destructive";
  if (outcome === "FAILURE") return "outline";
  return "secondary";
}

function instant(value) {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

function when(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : formatDateTime(date, { timeZoneName: "short" });
}

export default function AuditPanel({ api }) {
  const [page, setPage] = useState(null);
  const [search, setSearch] = useState("");
  const [action, setAction] = useState("");
  const [outcome, setOutcome] = useState("");
  const [targetType, setTargetType] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [first, setFirst] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState("");
  const listRequest = useRef(0);
  const detailRequest = useRef(0);

  const load = useCallback(async () => {
    const request = ++listRequest.current;
    const query = new URLSearchParams({ first, max: pageSize });
    if (search.trim().length >= 2) query.set("search", search.trim());
    if (action.trim()) query.set("action", action.trim());
    if (outcome) query.set("outcome", outcome);
    if (targetType) query.set("targetType", targetType);
    const after = instant(from);
    if (after) query.set("from", after);
    const before = instant(to);
    if (before) query.set("to", before);
    const data = await api(`/audit?${query}`);
    // A slow, older response must not overwrite a newer filter or page.
    if (request !== listRequest.current) return;
    setPage(data);
    setError("");
  }, [api, first, pageSize, search, action, outcome, targetType, from, to]);

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

  function filter(setter) {
    return (e) => {
      setter(e.target.value);
      setFirst(0);
    };
  }

  async function toggle(event) {
    const request = ++detailRequest.current;
    if (selected?.id === event.id) {
      setSelected(null);
      return;
    }
    setError("");
    try {
      const detail = await api(`/audit/${event.id}`);
      if (request !== detailRequest.current) return;
      setSelected(detail);
    } catch (e) {
      if (request === detailRequest.current) setError(e.message);
    }
  }

  const total = page?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.floor(first / pageSize) + 1;
  const shownFrom = total === 0 ? 0 : first + 1;
  const shownTo = Math.min(first + pageSize, total);

  return (
    <main id="audit" className="page-container">
      <PageHeader
        title="Audit log"
        actions={
          <Badge variant="outline">
            {total} {total === 1 ? "event" : "events"}
          </Badge>
        }
      />
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Audit events</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="audit-filters">
            <div className="form-field">
              <Label htmlFor="audit-search">Search the audit log</Label>
              <Input
                id="audit-search"
                type="search"
                placeholder="Search"
                value={search}
                onChange={filter(setSearch)}
              />
            </div>
            <div className="form-field">
              <Label htmlFor="audit-action">Action</Label>
              <Input
                id="audit-action"
                placeholder="Action, for example project.create"
                value={action}
                onChange={filter(setAction)}
              />
            </div>
            <div className="form-field">
              <Label htmlFor="audit-outcome">Outcome</Label>
              <Select
                value={outcome || "all"}
                onValueChange={(value) => {
                  setOutcome(value === "all" ? "" : value);
                  setFirst(0);
                }}
              >
                <SelectTrigger id="audit-outcome" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All outcomes</SelectItem>
                  {OUTCOMES.map((o) => (
                    <SelectItem key={o} value={o}>
                      {o}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="form-field">
              <Label htmlFor="audit-target">Target type</Label>
              <Select
                value={targetType || "all"}
                onValueChange={(value) => {
                  setTargetType(value === "all" ? "" : value);
                  setFirst(0);
                }}
              >
                <SelectTrigger id="audit-target" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All targets</SelectItem>
                  {TARGETS.map((t) => (
                    <SelectItem key={t} value={t}>
                      {t}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="form-field">
              <Label htmlFor="audit-from">From</Label>
              <Input
                id="audit-from"
                type="datetime-local"
                value={from}
                onChange={filter(setFrom)}
              />
            </div>
            <div className="form-field">
              <Label htmlFor="audit-to">To</Label>
              <Input
                id="audit-to"
                type="datetime-local"
                value={to}
                onChange={filter(setTo)}
              />
            </div>
          </div>
          {page === null ? (
            !error && <p role="status">Loading audit events…</p>
          ) : page.events.length === 0 ? (
            <div className="empty-state">
              <ScrollText size={28} aria-hidden="true" />
              <strong>No audit events</strong>
            </div>
          ) : (
            page.events.map((event) => {
              const expanded = selected?.id === event.id;
              return (
                <div className="audit-event" key={event.id}>
                  <div className="user-row">
                    <div className="project-name">
                      <strong>{event.summary}</strong>
                      <Badge
                        variant={outcomeVariant(event.outcome)}
                        className="ms-2"
                      >
                        {event.outcome}
                      </Badge>
                    </div>
                    <p className="project-url">
                      <span>{when(event.occurredAt)}</span>{" "}
                      <span className="user-email">{event.action}</span>
                      {event.actorUsername || event.actorSubject ? (
                        <span>
                          {" "}
                          by {event.actorUsername || event.actorSubject}
                        </span>
                      ) : null}
                      {event.projectName ? (
                        <span> in {event.projectName}</span>
                      ) : null}
                    </p>
                    <div className="user-tags">
                      <Badge variant="outline">{event.targetType}</Badge>
                      {event.targetName && (
                        <Badge variant="secondary">{event.targetName}</Badge>
                      )}
                      {event.statusCode != null && (
                        <Badge variant="outline">HTTP {event.statusCode}</Badge>
                      )}
                    </div>
                    <div className="user-actions">
                      <Button
                        type="button"
                        size="icon"
                        variant="outline"
                        title={expanded ? "Hide details" : "Show details"}
                        aria-label={`${expanded ? "Hide" : "Show"} details for event ${event.id}`}
                        aria-expanded={expanded}
                        aria-controls={
                          expanded ? `audit-detail-${event.id}` : undefined
                        }
                        onClick={() => toggle(event)}
                      >
                        <ScrollText size={16} aria-hidden="true" />
                      </Button>
                    </div>
                  </div>
                  {expanded && (
                    <div
                      className="audit-inline-detail"
                      id={`audit-detail-${event.id}`}
                    >
                      <div className="audit-inline-header">
                        <span className="audit-inline-title">
                          Event details
                        </span>
                        <Button
                          type="button"
                          size="icon"
                          variant="ghost"
                          title="Close details"
                          aria-label={`Close details for event ${event.id}`}
                          onClick={() => setSelected(null)}
                        >
                          <X size={16} aria-hidden="true" />
                        </Button>
                      </div>
                      <dl className="audit-detail">
                        <dt>When</dt>
                        <dd>{when(selected.occurredAt)}</dd>
                        <dt>Action</dt>
                        <dd>{selected.action}</dd>
                        <dt>Outcome</dt>
                        <dd>{selected.outcome}</dd>
                        <dt>Actor</dt>
                        <dd>
                          {selected.actorUsername || "—"}
                          {selected.actorSubject
                            ? ` (${selected.actorSubject})`
                            : ""}
                        </dd>
                        <dt>Roles</dt>
                        <dd>{(selected.actorRoles ?? []).join(", ") || "—"}</dd>
                        <dt>Project</dt>
                        <dd>{selected.projectName || "—"}</dd>
                        <dt>Target</dt>
                        <dd>
                          {selected.targetType}
                          {selected.targetName
                            ? `: ${selected.targetName}`
                            : ""}
                        </dd>
                        <dt>Request</dt>
                        <dd>
                          {[selected.httpMethod, selected.path]
                            .filter(Boolean)
                            .join(" ") || "—"}
                          {selected.requestId ? ` (${selected.requestId})` : ""}
                        </dd>
                      </dl>
                      <h3 className="audit-inline-subtitle">Details</h3>
                      <pre className="audit-json">
                        {JSON.stringify(selected.details ?? {}, null, 2)}
                      </pre>
                    </div>
                  )}
                </div>
              );
            })
          )}
          {total > 0 && (
            <div className="audit-paging">
              <div className="audit-page-size">
                <Label htmlFor="audit-page-size">Per page</Label>
                <Select
                  value={String(pageSize)}
                  onValueChange={(value) => {
                    setPageSize(Number(value));
                    setFirst(0);
                  }}
                >
                  <SelectTrigger
                    id="audit-page-size"
                    className="h-8 w-20"
                    aria-label="Page size"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {PAGE_SIZES.map((size) => (
                      <SelectItem key={size} value={String(size)}>
                        {size}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="audit-page-summary">
                <span>
                  Page {currentPage} of {totalPages}
                </span>
                <span>
                  {shownFrom}–{shownTo} of {total}
                </span>
              </div>
              <div className="audit-page-buttons">
                <Button
                  type="button"
                  size="icon"
                  variant="outline"
                  disabled={first === 0}
                  title="Previous"
                  aria-label="Previous page"
                  onClick={() => setFirst(Math.max(0, first - pageSize))}
                >
                  <ChevronLeft size={16} aria-hidden="true" />
                </Button>
                <Button
                  type="button"
                  size="icon"
                  variant="outline"
                  disabled={first + pageSize >= total}
                  title="Next"
                  aria-label="Next page"
                  onClick={() => setFirst(first + pageSize)}
                >
                  <ChevronRight size={16} aria-hidden="true" />
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
