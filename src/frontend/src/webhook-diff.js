/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

export const EVENTS = ["onFinished", "onFailed", "onFixed"];

/** Draft rows from the server list. `url` stays empty: existing URLs are only shown masked. */
export function toDraft(webhooks) {
  return (webhooks ?? []).map((w) => ({
    key: `id-${w.id}`,
    id: w.id,
    display: w.url,
    url: "",
    onFinished: !!w.onFinished,
    onFailed: !!w.onFailed,
    onFixed: !!w.onFixed,
  }));
}

/** A new, unsaved row. */
export function newRow(url, seq) {
  return {
    key: `new-${seq}`,
    id: null,
    display: url,
    url,
    onFinished: false,
    onFailed: true,
    onFixed: true,
  };
}

/** The creates, updates and deletes that turn the server list into the draft. */
export function diffWebhooks(original, draft) {
  const byId = new Map((original ?? []).map((w) => [w.id, w]));
  const creates = [];
  const updates = [];
  const kept = new Set();
  for (const row of draft ?? []) {
    if (row.id == null) {
      creates.push(row);
      continue;
    }
    kept.add(row.id);
    const before = byId.get(row.id);
    if (!before) continue;
    if (row.url.trim() || EVENTS.some((k) => !!before[k] !== !!row[k]))
      updates.push(row);
  }
  const deletes = (original ?? []).filter((w) => !kept.has(w.id));
  return {
    creates,
    updates,
    deletes,
    changed: creates.length + updates.length + deletes.length > 0,
  };
}

/** The request body for a row. */
export function body(row) {
  return JSON.stringify({
    url: row.url.trim() || null,
    onFinished: row.onFinished,
    onFailed: row.onFailed,
    onFixed: row.onFixed,
  });
}
