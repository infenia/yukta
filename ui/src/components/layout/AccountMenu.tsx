// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useMe } from '@/lib/identity';
import { requireApiKey, setApiKey } from '@/lib/apiKey';
import { useTheme, type Theme } from '@/lib/theme';

const THEMES: [Theme, string][] = [
  ['light', 'Light'],
  ['dark', 'Dark'],
  ['system', 'System'],
];

/** Phones: the account and the theme in one menu, as on the Sessions · phone artboard. */
export default function AccountMenu() {
  const me = useMe();
  const { theme, setTheme } = useTheme();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label="Account and theme"
        className="text-on-surface-variant hover:bg-muted hover:text-on-surface flex size-8 items-center justify-center rounded-full sm:hidden"
      >
        <span aria-hidden="true" className="material-symbols-outlined text-lg">
          person
        </span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {me?.authEnabled && (
          <>
            <DropdownMenuGroup>
              <DropdownMenuLabel>
                {me.keyName} · {me.role}
              </DropdownMenuLabel>
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
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
          </>
        )}
        <DropdownMenuGroup>
          <DropdownMenuLabel>Theme</DropdownMenuLabel>
          <DropdownMenuRadioGroup
            value={theme}
            onValueChange={(value) => setTheme(value as Theme)}
          >
            {THEMES.map(([value, label]) => (
              <DropdownMenuRadioItem key={value} value={value}>
                {label}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
