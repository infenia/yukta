# JTE Template Migration to Match session-list.html Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate JTE templates (`main.jte` and `index.jte`) to match the design and styling from `session-list.html` while maintaining dynamic data binding.

**Architecture:** The layout template (`main.jte`) will contain the Tailwind configuration and shared HTML structure (header, footer, theme toggle). The dashboard template (`index.jte`) will use the same dynamic data binding (sessions list iteration) but render session cards matching the exact design from `session-list.html`. The CSS file will be simplified to contain only the Tailwind import and theme configuration.

**Tech Stack:** JTE (Java Templating Engine), Tailwind CSS 4, Alpine.js, Material Symbols icons

---

## Task 1: Rewrite main.jte Layout Template

**Files:**
- Modify: `ui/src/main/jte/layout/main.jte`

**Step 1: Read the current main.jte**

Run: `cat ui/src/main/jte/layout/main.jte`

Expected: See the current layout with Tailwind CDN links and Alpine.js setup.

**Step 2: Read the design reference (session-list.html) for structure**

Run: `cat ui/src/main/design/html/session-list.html | head -100`

Expected: See the header, theme toggle structure, and inline Tailwind config.

**Step 3: Rewrite main.jte with inline Tailwind configuration**

Replace the entire file with:

```jte
@import gg.jte.Content
@param String title
@param Content content

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title} - Yukta</title>
    <script src="https://cdn.tailwindcss.com?plugins=forms,container-queries"></script>
    <link href="https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,100..1000;1,9..40,100..1000&family=Space+Grotesk:wght@300..700&display=swap" rel="stylesheet">
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:wght,FILL@100..700,0..1&display=swap" rel="stylesheet">
    <script>
        tailwind.config = {
            darkMode: "class",
            theme: {
                extend: {
                    colors: {
                        primary: "#3c83f6",
                        "background-light": "#f5f7f8",
                        "background-dark": "#0F172A",
                    },
                    fontFamily: {
                        display: ["DM Sans", "sans-serif"]
                    }
                }
            }
        }
    </script>
    <style>
        body { font-family: 'DM Sans', sans-serif; }
        .material-symbols-outlined { font-variation-settings: 'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24; }
    </style>
</head>
<body class="font-display bg-background-light dark:bg-background-dark text-slate-800 dark:text-slate-50 antialiased min-h-screen">

    <div class="max-w-[1200px] flex flex-col px-6 lg:px-10 min-h-screen mx-auto">
        <!-- Header -->
        <header class="flex items-center justify-between border-b sticky top-0 z-10 backdrop-blur-md bg-background-light/80 dark:bg-background-dark/80 border-slate-200 dark:border-slate-800 py-4">
            <a href="/ui" class="flex items-center gap-4">
                <div class="text-primary">
                    <span class="material-symbols-outlined text-4xl">settings_input_antenna</span>
                </div>
                <h1 class="text-xl font-bold tracking-tight">Yukta</h1>
            </a>

            <!-- Theme Toggle -->
            <div class="flex bg-white dark:bg-slate-900 p-1 rounded-3xl border border-slate-200 dark:border-slate-700 shadow-sm">
                <button class="p-1.5 rounded-full hover:bg-white/10 transition-all flex items-center justify-center text-slate-400" id="theme-light" onclick="setTheme('light')">
                    <span class="material-symbols-outlined text-[18px]">light_mode</span>
                </button>
                <button class="p-1.5 rounded-full hover:bg-white/10 transition-all flex items-center justify-center text-slate-400" id="theme-dark" onclick="setTheme('dark')">
                    <span class="material-symbols-outlined text-[18px]">dark_mode</span>
                </button>
                <button class="p-1.5 rounded-full hover:bg-white/10 transition-all flex items-center justify-center text-slate-400 border-l border-white/10 ml-1 pl-2" id="theme-system" onclick="setTheme('system')">
                    <span class="material-symbols-outlined text-[18px]">desktop_windows</span>
                </button>
            </div>
        </header>

        <main class="flex-grow py-6">
            ${content}
        </main>

        <footer class="mt-auto py-4 border-t border-slate-200 dark:border-slate-800 text-sm text-slate-500 flex justify-between items-center">
            <p>© 2026 Infenia Private Limited. Licensed under <a href="https://www.apache.org/licenses/LICENSE-2.0">Apache 2.0</a>.</p>
        </footer>
    </div>

    <script>
        function setTheme(theme) {
            const html = document.documentElement;
            if (theme === 'system') {
                localStorage.removeItem('theme');
                html.classList.toggle('dark', window.matchMedia('(prefers-color-scheme: dark)').matches);
            } else {
                localStorage.setItem('theme', theme);
                html.classList.toggle('dark', theme === 'dark');
            }
            updateThemeButtons();
        }

        function updateThemeButtons() {
            const current = localStorage.getItem('theme') || 'system';
            ['light', 'dark', 'system'].forEach(t => {
                const btn = document.getElementById(t);
                if (btn) btn.classList.toggle('bg-slate-100', current === t);
            });
        }

        // Initial theme
        if (localStorage.theme === 'dark' || (!localStorage.theme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark');
        }
        updateThemeButtons();
    </script>
</body>
</html>
```

**Step 4: Verify the file was written correctly**

Run: `cat ui/src/main/jte/layout/main.jte | head -50`

Expected: See the new Tailwind configuration and updated header structure.

**Step 5: Commit the layout changes**

```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta
git add ui/src/main/jte/layout/main.jte
git commit -m "refactor: update main.jte layout to match session-list design"
```

Expected: Commit succeeds.

---

## Task 2: Rewrite index.jte Dashboard Template

**Files:**
- Modify: `ui/src/main/jte/index.jte`

**Step 1: Read the design reference for session card structure**

Run: `sed -n '82,140p' ui/src/main/design/html/session-list.html`

Expected: See the session card HTML structure with badge, title, tags, and footer.

**Step 2: Rewrite index.jte to match design**

Replace the entire file with:

```jte
@import java.util.List
@import java.util.Map

@param List<Map<String, Object>> sessions

@template.layout.main(title = "Dashboard", content = @`
    <div class="mb-6">
        <h1 class="text-4xl font-black mb-2 text-slate-800 dark:text-slate-50">Active Sessions</h1>
        <p class="text-slate-500 dark:text-slate-300 text-lg max-w-2xl leading-relaxed">
            Manage and monitor your current engineering sessions and AI agent workflows.
        </p>
    </div>

    <!-- Search -->
    <div class="mb-6 max-w-xl">
        <div class="relative">
            <span class="material-symbols-outlined absolute left-5 top-1/2 -translate-y-1/2 text-slate-400">search</span>
            <input id="search-input" type="text"
                   class="w-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-3xl py-4 pl-14 pr-6 focus:outline-none focus:border-primary transition-all"
                   placeholder="Search sessions by ID, name, or initiator...">
        </div>
    </div>

    @if(sessions == null || sessions.isEmpty())
        <div class="text-center py-24 bg-white dark:bg-slate-900 rounded-3xl border border-dashed border-slate-200 dark:border-slate-700">
            <div class="w-20 h-20 mx-auto bg-slate-100 dark:bg-slate-800 rounded-full flex items-center justify-center text-slate-400 dark:text-slate-600 mb-6">
                <span class="material-symbols-outlined text-4xl">sensors_off</span>
            </div>
            <h3 class="text-xl font-bold text-slate-800 dark:text-white mb-2 uppercase tracking-widest">Ready for Vigilance</h3>
            <p class="text-slate-500 dark:text-slate-400 max-w-sm mx-auto">
                No active sessions found. Start an agent or a workflow to begin monitoring.
            </p>
        </div>
    @else
        <!-- Sessions Grid -->
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8" id="session-grid">
            @for(Map<String, Object> session : sessions)
                <div class="group p-5 relative bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm hover:shadow-xl transition-all duration-300 flex flex-col min-h-64 border-l-4 border-l-primary cursor-pointer"
                     onclick="window.location='/ui/sessions/${(String)session.get("sessionId")}'">

                    <div class="mb-4">
                        <span class="inline-flex items-center px-3.5 py-1.5 text-xs font-mono font-semibold bg-primary/10 text-primary truncate rounded-xl border border-primary/10">
                            #${(String)session.get("sessionId")}
                        </span>
                    </div>

                    <h3 class="text-lg font-semibold leading-tight text-slate-900 dark:text-slate-100 line-clamp-2 mb-5 min-h-2">
                        ${(String)session.getOrDefault("description", "No description provided.")}
                    </h3>

                    <div class="flex flex-wrap gap-2 mb-5">
                        @if(session.get("tags") instanceof Map tags)
                            @for(Object tagValue : tags.values())
                                <span class="inline-flex items-center px-3 py-1 text-xs font-medium bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-full border border-slate-200 dark:border-slate-700">
                                    ${String.valueOf(tagValue)}
                                </span>
                            @endfor
                        @endif
                    </div>

                    <div class="mt-auto pt-4 border-t border-slate-100 dark:border-slate-800 text-sm text-slate-600 dark:text-slate-400 flex justify-between items-center">
                        <div class="flex items-center gap-1.5">
                            <span class="text-slate-500 dark:text-slate-400">Initiator:</span>
                            <span class="font-medium">${(String)session.getOrDefault("initiator", "System")}</span>
                        </div>
                        <div class="text-right">
                            !{
                                String initiatedTime = (String) session.get("initiatedTime");
                                String formattedTime = "Unknown";
                                if (initiatedTime != null && !initiatedTime.isEmpty()) {
                                    try {
                                        java.time.Instant initiatedAt = java.time.Instant.parse(initiatedTime);
                                        java.time.ZonedDateTime zdt = initiatedAt.atZone(java.time.ZoneId.of("UTC"));
                                        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a");
                                        formattedTime = zdt.format(formatter);
                                    } catch (Exception e) {
                                        formattedTime = "Invalid date";
                                    }
                                }
                            }
                            <span class="text-slate-500 dark:text-slate-400">Initiated At</span>
                            <span class="font-medium">${formattedTime}</span>
                        </div>
                    </div>
                </div>
            @endfor
        </div>
    @endif

    <script>
        // Live search
        document.getElementById('search-input').addEventListener('input', e => {
            const term = e.target.value.toLowerCase();
            document.querySelectorAll('#session-grid > div').forEach(card => {
                card.style.display = card.textContent.toLowerCase().includes(term) ? '' : 'none';
            });
        });
    </script>
`);
```

**Step 3: Verify the file was written correctly**

Run: `cat ui/src/main/jte/index.jte | head -60`

Expected: See the new structure with session card markup and search functionality.

**Step 4: Commit the dashboard changes**

```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta
git add ui/src/main/jte/index.jte
git commit -m "refactor: update index.jte to match session-list design with dynamic data binding"
```

Expected: Commit succeeds.

---

## Task 3: Simplify input.css

**Files:**
- Modify: `ui/src/main/resources/static/css/input.css`

**Step 1: Read the current input.css**

Run: `cat ui/src/main/resources/static/css/input.css`

Expected: See the current file with @theme and custom utilities.

**Step 2: Simplify input.css to only required styles**

Replace the entire file with:

```css
@import "tailwindcss";

@theme {
  --color-primary: #3c83f6;
  --color-background-light: #f5f7f8;
  --color-background-dark: #0f172a;

  --font-display: "DM Sans", sans-serif;
  --font-heading: "Space Grotesk", sans-serif;
  --font-body: "DM Sans", sans-serif;

  --radius-DEFAULT: 0.5rem;
  --radius-lg: 0.75rem;
  --radius-xl: 1rem;
  --radius-2xl: 1.25rem;
}
```

**Step 3: Verify the file was written correctly**

Run: `cat ui/src/main/resources/static/css/input.css`

Expected: See only the import and theme configuration, no custom utilities.

**Step 4: Commit the CSS changes**

```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta
git add ui/src/main/resources/static/css/input.css
git commit -m "refactor: simplify input.css to only required Tailwind theme"
```

Expected: Commit succeeds.

---

## Task 4: Verify the Build

**Step 1: Clean and build the UI module**

Run: `cd /media/arun/Infenia/Infenia/Development/Public/yukta && ./gradlew :ui:clean :ui:build`

Expected: Build completes successfully without errors.

**Step 2: Check for any Spotless formatting issues**

Run: `./gradlew :ui:spotlessCheck`

Expected: No formatting issues detected.

**Step 3: Apply Spotless formatting if needed**

Run: `./gradlew spotlessApply`

Expected: Formatting applied successfully.

**Step 4: Commit any formatting changes**

Run: `git status`

Expected: Check if any files were modified by Spotless.

If files changed:
```bash
git add ui/src/main/jte/layout/main.jte ui/src/main/jte/index.jte
git commit -m "style: apply spotless formatting"
```

---

## Task 5: Run Full Build Verification

**Step 1: Run the full build with quality checks**

Run: `./gradlew check`

Expected: All tests pass, quality gates pass.

**Step 2: Test the application locally (optional)**

Run: `./gradlew bootRun`

Expected: Application starts on port 8080.

Navigate to: `http://localhost:8080/ui`

Expected: Dashboard loads with session cards matching the design.

**Step 3: Verify theme toggle works**

- Click light/dark/system theme buttons
- Verify CSS classes apply correctly
- Verify localStorage persists selection

Expected: Theme toggles work smoothly without page reload.

---

## Notes

- The search functionality is now inline in `index.jte` using vanilla JavaScript
- Theme management is entirely in `main.jte` using localStorage and system preference detection
- The CSS uses Tailwind's CDN for configuration flexibility
- All styling matches the reference design exactly
- Dynamic data binding preserved: sessionId, description, tags, initiator, initiatedTime
