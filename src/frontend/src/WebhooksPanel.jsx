/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { KeyRound, Plus, RotateCw, Trash2, Webhook } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import ConfirmDialog from "./components/layout/ConfirmDialog";
import { formatDateTime } from "./i18n";
import { EVENTS, newRow } from "./webhook-diff";

const MAX_WEBHOOKS = 10;
const COLUMNS = ["time", "build", "event", "status", "attempts", "response"];

function deliveryVariant(status) {
  if (status === "FAILED") return "destructive";
  if (status === "PENDING") return "outline";
  return "secondary";
}

function response(d) {
  // The HTTP status or the server-side error, shown as is.
  if (d.responseStatus != null) return `HTTP ${d.responseStatus}`;
  return d.error || "";
}

/** The Webhooks tab: rows are staged in the draft and applied by the page's Save. */
export default function WebhooksPanel({
  original,
  draft,
  deliveries,
  secrets,
  onDraftChange,
  onRotate,
  onRetry,
  loadError,
  busy,
}) {
  const { t } = useTranslation();
  const [url, setUrl] = useState("");
  const [seq, setSeq] = useState(1);
  const [confirmRotate, setConfirmRotate] = useState(null);

  function update(row, change) {
    onDraftChange(
      draft.map((r) => (r.key === row.key ? { ...r, ...change } : r)),
    );
  }

  function add(event) {
    event.preventDefault();
    if (!url.trim()) return;
    onDraftChange([...draft, newRow(url.trim(), seq)]);
    setSeq(seq + 1);
    setUrl("");
  }

  const lastById = new Map((original ?? []).map((w) => [w.id, w.lastDelivery]));

  return (
    <>
      <Card className="project-card">
        <CardHeader>
          <CardTitle>{t("webhooks.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          {loadError && (
            <div className="error-message" role="alert">
              <span className="block">{loadError}</span>
              {onRetry && (
                <Button
                  type="button"
                  variant="outline"
                  size="icon"
                  className="mt-3"
                  title={t("webhooks.retry")}
                  aria-label={t("webhooks.retry")}
                  onClick={onRetry}
                >
                  <RotateCw size={16} aria-hidden="true" />
                </Button>
              )}
            </div>
          )}
          {secrets.length > 0 && (
            <div className="success-message" role="status">
              {secrets.map((s) => (
                <p key={s.id} className="webhook-secret">
                  <span>{s.url}</span> <code>{s.secret}</code>
                </p>
              ))}
            </div>
          )}
          {original === null && !loadError ? (
            <p className="loading-state" role="status">
              {t("webhooks.loading")}
            </p>
          ) : loadError ? null : draft.length === 0 ? (
            <div className="empty-state">
              <Webhook size={28} aria-hidden="true" />
              <strong>{t("webhooks.empty")}</strong>
            </div>
          ) : (
            <ul className="members-list">
              {draft.map((row) => {
                const last = row.id != null ? lastById.get(row.id) : null;
                return (
                  <li className="webhook-row" key={row.key}>
                    <Input
                      type="url"
                      aria-label={t("webhooks.urlLabel")}
                      value={row.url}
                      placeholder={row.display}
                      disabled={busy}
                      onChange={(e) => update(row, { url: e.target.value })}
                    />
                    <span className="webhook-events">
                      {EVENTS.map((k) => (
                        <label key={k} className="webhook-event">
                          <input
                            type="checkbox"
                            checked={row[k]}
                            disabled={busy}
                            onChange={(e) =>
                              update(row, { [k]: e.target.checked })
                            }
                          />
                          {t(`webhooks.events.${k}`)}
                        </label>
                      ))}
                    </span>
                    {last ? (
                      <Badge
                        variant={deliveryVariant(last.status)}
                        title={response(last)}
                      >
                        {t(`webhooks.status.${last.status}`)}
                      </Badge>
                    ) : (
                      <span />
                    )}
                    <div className="member-actions">
                      {row.id != null && (
                        <Button
                          type="button"
                          variant="outline"
                          size="icon"
                          disabled={busy}
                          title={t("webhooks.rotate")}
                          aria-label={t("webhooks.rotateNamed", {
                            url: row.display,
                          })}
                          onClick={() => setConfirmRotate(row)}
                        >
                          <KeyRound size={16} aria-hidden="true" />
                        </Button>
                      )}
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        disabled={busy}
                        title={t("webhooks.remove")}
                        aria-label={t("webhooks.removeNamed", {
                          url: row.display,
                        })}
                        onClick={() =>
                          onDraftChange(draft.filter((r) => r.key !== row.key))
                        }
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          {!loadError && original !== null && (
            <form className="webhook-add" onSubmit={add}>
              <div className="form-field">
                <Label htmlFor="webhook-url">{t("webhooks.url")}</Label>
                <Input
                  id="webhook-url"
                  type="url"
                  value={url}
                  disabled={busy || draft.length >= MAX_WEBHOOKS}
                  placeholder="https://example.com/hooks/cisys"
                  onChange={(e) => setUrl(e.target.value)}
                />
              </div>
              <Button
                type="submit"
                size="icon"
                disabled={busy || !url.trim() || draft.length >= MAX_WEBHOOKS}
                title={t("webhooks.add")}
                aria-label={t("webhooks.add")}
              >
                <Plus size={16} aria-hidden="true" />
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
      {deliveries && deliveries.length > 0 && (
        <Card className="project-card">
          <CardHeader>
            <CardTitle>{t("webhooks.deliveries")}</CardTitle>
          </CardHeader>
          <CardContent>
            <table className="webhook-deliveries">
              <thead>
                <tr>
                  {COLUMNS.map((c) => (
                    <th key={c}>{t(`webhooks.columns.${c}`)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {deliveries.map((d) => (
                  <tr key={d.id}>
                    <td>
                      {formatDateTime(new Date(d.lastAttemptAt || d.createdAt))}
                    </td>
                    <td>{d.buildNumber != null ? `#${d.buildNumber}` : ""}</td>
                    <td>{d.event}</td>
                    <td>
                      <Badge variant={deliveryVariant(d.status)}>
                        {t(`webhooks.status.${d.status}`)}
                      </Badge>
                    </td>
                    <td>{d.attempts}</td>
                    <td className="webhook-response">{response(d)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}
      <ConfirmDialog
        open={confirmRotate !== null}
        title={t("webhooks.rotate")}
        message={confirmRotate ? confirmRotate.display : ""}
        confirmLabel={t("webhooks.rotateConfirm")}
        onConfirm={() => {
          onRotate(confirmRotate);
          setConfirmRotate(null);
        }}
        onCancel={() => setConfirmRotate(null)}
      />
    </>
  );
}
