IMPORTANT: Never enter PLAN mode unless I explicitly requested!

# GhostClip

Android <-> Mac clipboard sync tool. User copies text on one device, it appears on the other.

## Architecture

- **Cloudflare Worker** (`worker/`): Cloud relay. KV-backed REST API for clipboard data + device presence.
- **Mac Tauri v2 App** (`mac/`): Menu Bar app. Rust backend (NSPasteboard, mDNS, WebSocket server) + Web frontend (settings UI).
- **Android App** (`android/`): Kotlin. Foreground clipboard read via floating ball trigger + network sync.

## Key Design Decisions

- Android clipboard capture: **Floating ball manual trigger** (background detection failed on HyperOS 3.0). onResume + onWindowFocusChanged auto-read.
- Network: LAN direct (mDNS + WebSocket) preferred, cloud (Cloudflare KV polling) as fallback.
- Sync direction: Android -> Mac auto, Mac -> Android via Cmd+Shift+C.
- Dedup: MD5 hash pool (LRU, 3s TTL) on both ends.
- Auth: Bearer token set via `wrangler secret`.

## Design Specs

- UI designs: `design/ghostclip-ui.pen` (use Pencil MCP tools to read, NOT Read tool)
- OpenSpec artifacts: `openspec/changes/ghostclip-mvp/` (proposal, design, specs, tasks)
- Design system: #22C55E green accent, Space Grotesk + Inter fonts

## Agent Team Roles

| Role | Directory Ownership | Conflict Rules |
|------|-------------------|----------------|
| worker-dev | `worker/` | Sole owner of worker/ |
| mac-dev | `mac/src-tauri/` (Rust backend) | No UI files |
| android-dev | `android/` (Kotlin logic) | No UI layout files after ui-dev starts |
| ui-dev | `mac/src/` (web frontend) + Android UI files | Coordinates with platform devs |
| reviewer | Read-only, no file edits | Reviews via messages only |

## Rules for All Teammates

1. Read `openspec/changes/ghostclip-mvp/design.md` and relevant `specs/` before starting.
2. Check TaskList after completing each task to find next work.
3. Never edit files outside your owned directory without coordinating first.
4. Use conventional commits: `feat(scope):`, `fix(scope):`, `refactor(scope):`.
5. Language: Chinese for comments and UI text, English for code identifiers.
