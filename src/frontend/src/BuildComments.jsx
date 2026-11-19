/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from "react";
import { Pencil } from "lucide-react";
import { Button } from "./components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import { Textarea } from "./components/ui/textarea";

/** Comments on a build; members may add one and authors may edit theirs. */
export default function BuildComments({
  name,
  projectId,
  buildId,
  canComment,
  load,
}) {
  const [comments, setComments] = useState([]);
  const [draft, setDraft] = useState("");
  const [editing, setEditing] = useState(null);
  const [editText, setEditText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const loadComments = useCallback(async () => {
    try {
      setComments(
        await load(
          `/project-pages/${encodeURIComponent(name)}/builds/${buildId}/comments`,
        ),
      );
    } catch (e) {
      setError(e.message || "Unable to load the comments.");
    }
  }, [load, name, buildId]);

  useEffect(() => {
    const initial = setTimeout(loadComments, 0);
    return () => clearTimeout(initial);
  }, [loadComments]);

  async function save(path, method, body) {
    setBusy(true);
    setError("");
    try {
      await load(path, { method, body: JSON.stringify({ body }) });
      await loadComments();
      return true;
    } catch (e) {
      setError(e.message || "Unable to save the comment.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  const base = `/projects/${projectId}/builds/${buildId}/comments`;

  async function add(event) {
    event.preventDefault();
    if (await save(base, "POST", draft)) setDraft("");
  }

  async function update(event) {
    event.preventDefault();
    if (await save(`${base}/${editing}`, "PUT", editText)) setEditing(null);
  }

  return (
    <Card className="project-card">
      <CardHeader>
        <CardTitle>Comments</CardTitle>
      </CardHeader>
      <CardContent>
        {error && (
          <p className="member-add-error" role="alert">
            {error}
          </p>
        )}
        {comments.length > 0 && (
          <ul className="comment-list">
            {comments.map((c) => (
              <li key={c.id} className="comment-row">
                <div className="comment-meta">
                  <span className="comment-author">{c.author}</span>
                  <span>{new Date(c.createdAt).toLocaleString()}</span>
                  {c.updatedAt && <span>(edited)</span>}
                  {c.mine && editing !== c.id && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="comment-edit"
                      onClick={() => {
                        setEditing(c.id);
                        setEditText(c.body);
                      }}
                      title="Edit"
                      aria-label="Edit comment"
                    >
                      <Pencil size={14} aria-hidden="true" />
                    </Button>
                  )}
                </div>
                {editing === c.id ? (
                  <form className="comment-form" onSubmit={update}>
                    <Textarea
                      aria-label="Comment"
                      value={editText}
                      onChange={(e) => setEditText(e.target.value)}
                    />
                    <div className="comment-form-actions">
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => setEditing(null)}
                      >
                        Cancel
                      </Button>
                      <Button type="submit" disabled={busy || !editText.trim()}>
                        Save
                      </Button>
                    </div>
                  </form>
                ) : (
                  <p className="comment-body">{c.body}</p>
                )}
              </li>
            ))}
          </ul>
        )}
        {canComment && (
          <form className="comment-form comment-add" onSubmit={add}>
            <Textarea
              aria-label="New comment"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <div className="comment-form-actions">
              <Button type="submit" disabled={busy || !draft.trim()}>
                Comment
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
