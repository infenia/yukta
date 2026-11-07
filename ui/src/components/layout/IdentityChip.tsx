// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useMe } from '@/lib/identity';
import { requireApiKey, setApiKey } from '@/lib/apiKey';

/** Who the key in use is and its role; changes or forgets the key. Hidden when no key is needed. */
export default function IdentityChip() {
  const me = useMe();
  if (!me?.authEnabled) return null;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label="Account"
        className="mr-1 flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-body-sm text-muted-foreground hover:bg-muted hover:text-foreground"
      >
        <span className="material-symbols-outlined text-base">key</span>
        <span className="hidden sm:inline">{me.keyName}</span>
        <span className="rounded bg-muted px-1.5 py-0.5 text-label-sm">
          {me.role}
        </span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => requireApiKey()}>
          Change API key
        </DropdownMenuItem>
        <DropdownMenuItem
          onClick={() => {
            setApiKey(null);
            window.location.reload();
          }}
        >
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
