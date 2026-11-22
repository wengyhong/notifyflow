# NotifyFlow frontend

## Milestone 9 checkpoint

The SvelteKit/TypeScript dashboard provides:

- Registration, session login/logout, and a shared CSRF helper.
- Current-user/workspace discovery through `GET /api/auth/me` and workspace selection.
- Paginated endpoint management, enable/disable, subscriptions, and secret rotation.
- Producer key creation with one-time raw-key display, metadata pages, and revocation.
- Paginated delivery history, status filtering, HTTP attempt details, and failed-delivery replay.
- A shared visual layout, responsive styles, accessible form labels, and status badges.

Admins can manage resources and replay failed deliveries. Viewers can read
endpoints, subscriptions, deliveries, and attempts. The backend enforces those
rules using the authenticated user's memberships; the selected workspace ID
alone never grants access.

The dashboard includes new tenant-scoped read APIs for subscriptions, producer
keys, deliveries, and attempts. Delivery queries require both the event and
endpoint to belong to the selected workspace. Response DTOs keep persistence
relationships, payloads, password hashes, and signing secrets out of read APIs.

Browser walkthroughs verified registration/login/logout; endpoint creation and
enable/disable; subscription creation and duplicate handling; secret rotation;
producer key creation/hiding/revocation; delivery outcomes; and manual replay
from HTTP 400 to HTTP 204 while retaining attempt history. See
[authentication](../docs/authentication.md) and
[manual replay](../docs/manual-replay.md) for the backend contracts and examples.

Checkpoint verification on 2026-11-22: backend `mvn -o clean verify` passed all
1,382 tests with no failures, errors, or skips. This includes current-user HTTP
and database tests for session identity, workspace membership isolation, lazy
relationship mapping, and empty memberships.

The 70 automated frontend tests run across 13 files with mocked requests.
`npm run check` reports no errors or warnings, and `npm run build` passes.
The development proxy targets Spring Boot on port 7829. Production adapter/API
proxy deployment is a later deployment task; user sessions currently live in
the servlet container and the history views use manual refresh rather than polling.

## NotifyFlow frontend tests

Run the complete suite from this directory:

```powershell
npm test
```

`npm run test:unit` starts watch mode. To run one group once, for example:

```powershell
npm run test:unit -- --run src/routes/login/page.svelte.spec.ts
```

Plain `.spec.ts` tests run in Node. `.svelte.spec.ts` tests run Svelte components
in headless Chromium through Vitest's Playwright provider. Install that browser
on a new machine with `npx playwright install chromium` after `npm ci`.

Tests cover CSRF retrieval; login, registration, and logout; endpoint creation
and enable/disable; subscription creation/listing; rotation and secret hiding;
producer key creation/listing/revocation; delivery filtering and pagination;
attempt details and replay; viewer restrictions; and workspace changes that
clear secrets and ignore older responses.

`src/lib/testing/api.ts` supplies fake API responses and test-only fixtures.
Tests replace `fetch` and never require a running Spring Boot application or
use development credentials. Generated greeting examples have been replaced
with NotifyFlow tests. Type checking remains available with `npm run check`.

## NotifyFlow visual layout

Shared styles live in `src/app.css`, imported by the root layout. The interface
uses a green workspace sidebar, endpoint cards, delivery/key tables, semantic
status badges, and responsive layouts. Sidebar links jump to the existing
sections without replacing the selected workspace or unmounting their forms.

`Brand`, `Icon`, and `StatusBadge` provide reusable visual elements. `AuthShell`
gives login and registration a shared two-column layout that stacks on smaller
screens. Fonts use DM Sans and Manrope from Google Fonts with local fallbacks.
No UI framework dependencies were added.

Mobile layouts keep logout accessible, stack forms/cards, and let tables scroll
within their containers. Keyboard focus indicators, a skip link, and reduced
motion styles are included. The API request handlers and access checks remain
in their existing components.

## NotifyFlow producer API key management

The dashboard's **Producer API keys** section is available to workspace admins.
It uses the current user session and the selected workspace's `X-Tenant-ID`.
The Vite development proxy forwards `/api` requests to Spring Boot; update its
target in `vite.config.ts` if the backend port changes.

- Create a key with a nonblank name of at most 100 characters. Save its raw value
  privately, then choose **Hide key**. The raw key is held only in component
  memory and cannot be retrieved after hiding, navigation, or workspace changes.
  Save and hide the current key before creating another.
- The paginated list shows key IDs, names, creation times, and active/revoked
  status. The GET response contains neither raw keys nor stored hashes.
- **Revoke key** prevents subsequent producer authentication with that key.
  Revoked keys remain visible for reference, with their revocation time.
  Revoking a newly created key also clears its raw value from the display.
- Creation and revocation fetch fresh CSRF tokens through `src/lib/api/csrf.ts`.
  These user-session operations are separate from producer Bearer authentication.

Browser verification: create a disposable demo key, save it privately, verify that
its metadata remains after **Hide key** and a page refresh, then revoke it and
verify the **Revoked** status. The backend tests separately verify that revoked
keys cannot authenticate publishing requests and cannot cross tenant boundaries.

## NotifyFlow delivery history

The selected workspace's delivery history is available to admins and viewers.
Use the status filter, paginated list, and **View details and attempts** to inspect
delivery status, retry timing, and paginated HTTP attempt outcomes. Reads use the
user session and `X-Tenant-ID` without a CSRF token. Both sides of a delivery's
event/endpoint relationship must belong to the selected workspace.

Admins can choose **Replay failed delivery** in a failed delivery's details.
This fetches a fresh CSRF token and calls the existing replay POST. Acceptance
refreshes the list and details; further processing runs in the background.
Use the refresh controls to check subsequent progress. The dashboard does not
automatically poll. Workspace changes reset the history and selected delivery.
See [delivery history and replay](../docs/manual-replay.md) for API details.

Everything you need to build a Svelte project, powered by [`sv`](https://github.com/sveltejs/cli).

## Creating a project

If you're seeing this, you've probably already done this step. Congrats!

```sh
# create a new project
npx sv create my-app
```

To recreate this project with the same configuration:

```sh
# recreate this project
npx sv@1.7.0 create --template minimal --types ts --install npm frontend
```

## Adding features

Add features to your project with `sv add`:

```sh
npx sv add
```

For example, to add Tailwind CSS:

```sh
npx sv add tailwindcss
```

## Developing

Once you've created a project and installed dependencies with `npm install` (or `pnpm install` or `yarn`), start a development server:

```sh
npm run dev

# or start the server and open the app in a new browser tab
npm run dev -- --open
```

## Building

To create a production version of your app:

```sh
npm run build
```

You can preview the production build with `npm run preview`.

> To deploy your app, you may need to install an [adapter](https://svelte.dev/docs/kit/adapters) for your target environment.
