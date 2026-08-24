# Frontend Working Guide

This file applies to everything under `frontend/`. Follow the repository-root `AGENTS.md` as well; this guide adds frontend-specific rules.

## Scope and architecture

- React 19 application built with Vite.
- `src/App.jsx` owns the top-level application layout.
- `src/api/client.js` is the boundary for backend HTTP calls.
- `src/hooks/useChat.js` owns chat state and request orchestration.
- Reusable UI lives in `src/components/`; keep each component's styles in its adjacent CSS module.
- Global styles belong in `src/index.css`. Prefer CSS modules for component-specific styling.

## Development rules

- Use functional React components and hooks.
- Keep network request and response normalization in `src/api/client.js`; do not scatter fetch calls across components.
- Preserve the backend chat contract, including `query`, `pipelineRunId`, `sessionId`, and `userId`, and handle nullable session IDs.
- Treat backend answers, sources, and research-log text as untrusted content. Do not render backend text as raw HTML.
- Preserve accessible labels, keyboard behavior, focus states, and semantic controls when changing the UI.
- Keep loading, empty, error, and unavailable-backend states explicit.
- Do not commit `node_modules/` or `dist/`.

## Commands and verification

Run commands from `frontend/`:

```powershell
npm install
npm run dev
npm run lint
npm run build
```

For ordinary frontend changes, run both `npm run lint` and `npm run build`. Manually exercise the affected interaction when behavior changes, especially chat submission, run selection, source display, session reuse, and backend-unavailable states.

## Change guidance

- Prefer small, focused components over expanding `App.jsx`.
- Avoid adding dependencies when React, browser APIs, or existing utilities are sufficient.
- Keep API endpoint assumptions centralized and compatible with the Javalin backend on port `7070`.
- If the backend response shape changes, update the client boundary and all affected UI states together.
- Update this file when frontend commands, architecture, or operational expectations change.
