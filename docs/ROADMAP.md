# Roadmap

## Fase 0 — Bootstrap ✅ (esta sesión)

- Repo `WilmanTech/mm-mobile` creado
- KMP project structure: `shared/` + `android/` + `ios/`
- AGENTS.md, README.md, .editorconfig, .gitignore, CI workflow
- Theme MM dark + accent yellow en Compose
- 4 tab UI (Home, Search, Library, Settings) + Pairing placeholder
- ConnectivityMonitor interfaces + Android/iOS actuals
- Domain entities (Artist, Album, Track, Playlist, DownloadState)
- SQLDelight schema v1 (10 tablas, offline-first)
- 1 test smoke

## Fase 1 — Pairing + Sync metadata

- `core:network` — Ktor client con auth bearer, endpoints v1
- `core:pairing` — flujo QR + manual host:port, persistente cifrado
- `core:data` — repos SQLDelight, sync incremental desde backend
- Backend: implementar `/api/v1/sync/changes?since=...` (auditar gaps)

## Fase 2 — Browse + Search

- UI Home (recently played, new releases)
- UI Browse (grid artistas/albumos/playlist)
- UI Search (online-first, fallback local)
- Streaming online (Media3 ExoPlayer / AVPlayer)
- Mini-player persistente

## Fase 3 — Downloads + Offline playback

- `core:download` — cola persistente (SQLDelight)
- WorkManager (Android) + BGTaskScheduler (iOS)
- Player offline-first (local storage lookup)
- Storage management UI
- Settings: auto-download, WiFi-only, storage limit

## Fase 4 — Polish

- Now Playing full screen + queue management
- Library: playlists, favorites
- Sync bidireccional de play progress
- Lyrics (si backend los soporta)
- Tests E2E: pairing, sync, download, playback offline

## v2

- Smart playlists sync (reglas en backend)
- Multi-device handoff
- CarPlay / Android Auto
- Cast (Chromecast, AirPlay)
- Equalizer + audio effects
