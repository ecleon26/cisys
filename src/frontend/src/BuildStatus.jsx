/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from 'react';
import { Ban, Circle, Clock, RefreshCw, ThumbsDown, ThumbsUp } from 'lucide-react';

const LABELS = { PASSED: 'Passed', FAILED: 'Failed', RUNNING: 'Running', QUEUED: 'Queued', CANCELLED: 'Cancelled' };

/** Thumbs up for a passing build, thumbs down for a failing one, a spinner while it runs. */
export default function BuildStatus({ status }) {
  const label = LABELS[status] || status || 'Unknown';
  const common = { role: 'img', 'aria-label': `Build ${label}`, title: label };
  if (status === 'PASSED') {
    return <span className="build-status build-status-pass" {...common}><ThumbsUp size={16} aria-hidden="true"/></span>;
  }
  if (status === 'FAILED') {
    return <span className="build-status build-status-fail" {...common}><ThumbsDown size={16} aria-hidden="true"/></span>;
  }
  if (status === 'QUEUED') {
    return <span className="build-status build-status-other" {...common}><Clock size={16} aria-hidden="true"/></span>;
  }
  if (status === 'CANCELLED') {
    return <span className="build-status build-status-other" {...common}><Ban size={16} aria-hidden="true"/></span>;
  }
  if (status === 'RUNNING') {
    return <span className="build-status build-status-running" {...common}><RefreshCw size={16} className="animate-spin" aria-hidden="true"/></span>;
  }
  return <span className="build-status build-status-other" {...common}><Circle size={14} aria-hidden="true"/></span>;
}
