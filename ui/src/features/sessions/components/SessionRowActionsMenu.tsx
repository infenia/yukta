// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { EXPORT_HINT, exportSessionConfig } from '../lib/exportSession';
import type { SessionListItem } from '../types/session';

interface SessionRowActionsMenuProps {
  session: SessionListItem;
}

export function SessionRowActionsMenu({ session }: SessionRowActionsMenuProps) {
  const navigate = useNavigate();
  const [exportFailed, setExportFailed] = useState(false);

  const handleView = () => {
    navigate(`/sessions/${session.sessionId}`);
  };

  return (
    <>
      {exportFailed && (
        <span role="alert" className="text-error text-body-sm mr-2">
          Export failed
        </span>
      )}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={(state) => (
            <Button
              variant="ghost"
              size="sm"
              aria-label="Session actions"
              {...state}
              onClick={(e: React.MouseEvent) => {
                e.stopPropagation();
                state.onClick?.(e);
              }}
            >
              <span className="material-symbols-outlined" aria-hidden="true">
                more_vert
              </span>
            </Button>
          )}
        />
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            onClick={(e) => {
              e.stopPropagation();
              handleView();
            }}
          >
            View Details
          </DropdownMenuItem>
          <DropdownMenuItem
            title={EXPORT_HINT}
            onClick={(e) => {
              e.stopPropagation();
              setExportFailed(false);
              exportSessionConfig(session.sessionId).catch(() =>
                setExportFailed(true)
              );
            }}
          >
            Export JSON
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
