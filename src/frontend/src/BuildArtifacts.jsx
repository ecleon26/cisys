/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Download, FileArchive } from "lucide-react";
import { Button } from "./components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";

export function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

/**
 * The build's artifacts. Downloads go through fetch, as the API needs the bearer token; visitors
 * who are not signed in get a 401 and see nothing.
 */
export default function BuildArtifacts({ name, buildId, load }) {
  const [artifacts, setArtifacts] = useState(null);
  const [error, setError] = useState("");
  const base = `/project-pages/${encodeURIComponent(name)}/builds/${buildId}/artifacts`;

  useEffect(() => {
    let live = true;
    load(base)
      .then((r) => live && setArtifacts(r))
      .catch((e) => {
        if (live && e.status !== 401 && e.status !== 404) {
          setError(e.message || "Unable to load the artifacts.");
        }
      });
    return () => {
      live = false;
    };
  }, [load, base]);

  async function download(path, fallback) {
    setError("");
    try {
      const response = await load(path, {
        raw: true,
        headers: { Accept: "*/*" },
      });
      const disposition = response.headers.get("Content-Disposition") || "";
      const file = /filename="([^"]+)"/.exec(disposition)?.[1] || fallback;
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = file;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) {
      setError(e.message);
    }
  }

  if (error) {
    return <p role="alert">{error}</p>;
  }
  const files = artifacts?.files || [];
  if (files.length === 0) {
    return null;
  }
  return (
    <Card className="project-card">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Artifacts</CardTitle>
        <Button
          type="button"
          size="icon"
          onClick={() => download(`${base}.zip`, "artifacts.zip")}
          title="Download all"
          aria-label="Download all"
        >
          <FileArchive aria-hidden="true" />
        </Button>
      </CardHeader>
      <CardContent>
        <ul className="build-artifacts">
          {files.map((f) => (
            <li key={f.id} className="flex items-center justify-between gap-2">
              <span className="font-mono break-all">{f.path}</span>
              <span className="flex items-center gap-2">
                <span>{formatSize(f.size)}</span>
                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  onClick={() =>
                    download(
                      `${base}/${f.id}`,
                      f.path.slice(f.path.lastIndexOf("/") + 1),
                    )
                  }
                  title={`Download ${f.path}`}
                  aria-label={`Download ${f.path}`}
                >
                  <Download aria-hidden="true" />
                </Button>
              </span>
            </li>
          ))}
        </ul>
        {artifacts.truncated && <p>The artifacts were cut at their limit.</p>}
      </CardContent>
    </Card>
  );
}
