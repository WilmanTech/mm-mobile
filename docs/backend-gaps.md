# Backend gaps para mm-mobile

Auditoría de qué endpoints necesita la app móvil del backend de
MusicManager (`WilmanTech/MusicManager`).

## Estado (Fase 1.1 entregada en `feature/mobile-sync-api`)

### Pairing

| Endpoint | Estado |
|---|---|
| `POST /api/pairing/start` | ✅ existe |
| `GET /api/pairing/qr` | ✅ existe |
| `POST /api/pairing/confirm` | ✅ existe |
| `GET /api/pairing/status` | ✅ existe |
| `GET /api/pairing/devices` | ✅ existe |
| `DELETE /api/pairing/devices/{session_id}` | ✅ existe |
| `GET /api/v1/whoami` | ✅ existe |
| `GET /api/v1/ping` | ✅ existe |

### Sync de biblioteca (Fase 1.1 — listo)

| Endpoint | Estado |
|---|---|
| `GET /api/v1/sync/changes?since=<epoch_ms>` | Deltas incrementales de catálogo |
| `GET /api/v1/sync/full` | Snapshot completo (bootstrap inicial) |

**Estado Phase 1.1** (entregado `develop` de MusicManager backend, ver
`WilmanTech/MusicManager@feature/mobile-sync-api`):

- ✅ `GET /api/v1/sync/full` — retorna todos los artistas/albums/tracks/playlists
- ✅ `GET /api/v1/sync/changes?since=<epoch_ms>` — filtra por `updated_at`
- ✅ Header `X-Since` con timestamp epoch_ms (no ISO8601 — corregido)
- ✅ Response shape con `server_time`, `has_more`, todas las 5 listas

**Tests cliente** (`mm-mobile/shared/src/jvmTest`): SyncCoordinatorTest
verifica que el cliente envía el header correcto y persiste `serverTime`
para el siguiente delta.

`updated_at` lo mantiene un par de triggers `AFTER INSERT/UPDATE`
(instalados idempotentemente en `database.py:_add_updated_at`). El
`/sync/changes` filtra con `WHERE updated_at > ?` usando un índice.

### Streaming de audio

| Endpoint | Estado |
|---|---|
| `GET /api/stream/{track_id}` | ✅ existe (sin auth bearer — usable por TV client sin pairing) |

Tiene `Accept-Ranges: bytes` y maneja range requests. **No requiere
cambios para Fase 1**. La app mobile lo llama con bearer cuando está
paired; sin bearer también funciona para casos degradados (e.g. TV
export).

### Cover art

| Endpoint | Estado |
|---|---|
| `GET /api/library/covers/{cover_path:path}` | ✅ existe (sin auth) |

Sirve la imagen directamente desde el filesystem. No requiere cambios
para Fase 1. La URL se construye a partir de `album.cover_path` que
llega en el `/sync/full`.

### Pendiente para Fase 2+

| Endpoint | Propósito |
|---|---|
| `POST /api/v1/playback/progress` | `{ track_id, position_ms, completed }` — sync de progreso |
| `GET /api/v1/devices/me`, `PATCH` | Info + metadata del dispositivo (push token, etc.) |

## Decisión de auth

Hoy el backend acepta `X-Pairing-Token` y `Authorization: Bearer ***`. La
app mobile **siempre envía bearer**. El header legacy se mantiene por
compatibilidad con `tv-library-client`.

## Tombstones

`/sync/changes` no representa hard deletes — la fila simplemente
desaparece del response. El cliente mobile usa upsert-by-id, así que
filas ausentes = borrado local. Si en el futuro queremos notificaciones
explícitas, añadir un campo `deleted_ids: []` al envelope.
