// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
} from '@/components/ui/dropdown-menu';
import {
  toCommand,
  type CommandAction,
  type CommandFormat,
} from '@/lib/commandEquivalents';

/** One action that can be copied, with the label the menu shows for it. */
export interface CopyChoice {
  label: string;
  action: CommandAction;
}

const FORMATS: { format: CommandFormat; label: string }[] = [
  { format: 'cli', label: 'Copy as CLI' },
  { format: 'curl', label: 'Copy as curl' },
  { format: 'mcp', label: 'Copy as MCP' },
];

/** Menu entries "Copy as CLI / curl / MCP", each listing the actions that can be copied in that form. */
export function CopyAsMenu({
  choices,
  onCopy,
}: {
  choices: CopyChoice[];
  onCopy: (text: string) => void;
}) {
  return (
    <>
      {FORMATS.map(({ format, label }) => (
        <DropdownMenuSub key={format}>
          <DropdownMenuSubTrigger>{label}</DropdownMenuSubTrigger>
          <DropdownMenuSubContent>
            {choices.map((choice) => (
              <DropdownMenuItem
                key={choice.label}
                onClick={() =>
                  onCopy(
                    toCommand(format, choice.action, window.location.origin)
                  )
                }
              >
                {choice.label}
              </DropdownMenuItem>
            ))}
          </DropdownMenuSubContent>
        </DropdownMenuSub>
      ))}
    </>
  );
}
