/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import { BookOpen } from "lucide-react";
import { manualPath } from "./project-paths";

/** Header icon that opens the manual. */
export default function ManualButton({ active, onOpen }) {
  return (
    <a
      href={manualPath()}
      className="header-icon"
      aria-label="Manual"
      title="Manual"
      aria-current={active ? "page" : undefined}
      onClick={(event) => {
        event.preventDefault();
        onOpen();
      }}
    >
      <BookOpen size={20} aria-hidden="true" />
    </a>
  );
}
