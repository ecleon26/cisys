/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { ChevronDown, CircleUserRound, LogIn, Moon, Sun } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "./components/ui/dropdown-menu";
import AppFooter from "./components/layout/AppFooter";
import ManualButton from "./ManualButton";
import PublicStats from "./PublicStats";
import headerLogo from "./assets/logo-header.svg";

/** Landing page for visitors who are not signed in. `children`, when given, replace the statistics (for example a project page). */
export default function PublicHome({
  branding,
  stats,
  statsError,
  logoUrl,
  onSignIn,
  dark,
  setDark,
  onOpenManual,
  manualActive,
  children,
}) {
  // One-way flag: once the logo request fails (404 when none is set) we use the bundled logo and never retry.
  const [logoFailed, setLogoFailed] = useState(false);

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="header-main">
          <a className="brand" href="/" aria-label={`${branding.name} home`}>
            <img
              src={logoFailed ? headerLogo : logoUrl}
              alt=""
              className={
                logoFailed ? "brand-logo brand-logo-default" : "brand-logo"
              }
              onError={() => setLogoFailed(true)}
            />
            <span>{branding.name}</span>
          </a>
        </div>
        <div className="header-actions">
          <ManualButton active={manualActive} onOpen={onOpenManual} />
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
              <DropdownMenuItem onSelect={onSignIn}>
                <LogIn aria-hidden="true" /> Sign in
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      {children || (
        <main id="public-home" className="page-container">
          <PublicStats stats={stats} statsError={statsError} />
        </main>
      )}
      <AppFooter />
    </div>
  );
}
