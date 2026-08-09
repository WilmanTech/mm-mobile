# Backend gaps para mm-mobile

Auditoría rápida de qué endpoints necesita la app móvil del backend de
MusicManager (`WilmanTech/MusicManager`). Estado al crear este repo.

## Ya existen (parity con VideoManager)

| Endpoint | Uso en mobile |
|---|---|
| `POST /api/pairing/start` | Iniciar pairing desde QR display |
| `GET /api/pairing/qr` | Renderizar QR en pantalla de display |
| `POST /api/pairing/confirm` | Confirmar emparejamiento |
| `GET /api/pairing/status` | Polling mientras el usuario escanea |
| `GET /api/pairing/devices` | Listar dispositivos emparejados |
| `DELETE /api/pairing/devices/{session_id}` | Olvidar dispositivo |
| `GET /api/v1/whoami` | Verificar bearer token |
| `GET /api/v1/ping` | Keep-alive (auto-reconnect) |

## Falta implementar (necesarios para mobile)

### Sync de biblioteca

| Endpoint | Propósito |
|---|---|
| `GET /api/v1/sync/changes?since=<iso8601>` | Deltas incrementales de catálogo |
| `GET /api/v1/sync/full` | Snapshot completo (bootstrap inicial) |

**Response shape** (propuesta):

```json
{
  "server_time": "2026-08-09T12:00:00Z",
  "artists": [{ "id": 1, "name": "...", "updated_at": "...", "...": "..." }],
  "albums":  [{ "id": 1, "title": "...", "artist_id": 1, "..." }],
  "tracks":  [{ "id": 1, "title": "...", "album_id": 1, "..." }],
  "playlists": [...]
}
```

### Streaming de audio

| Endpoint | Propósito |
|---|---|
| `GET /api/v1/tracks/{id}/stream?token=<bearer>` | URL firmada para reproducir/descargar |

El response debe ser 302 redirect a un URL local del servidor (que sirve
desde SMB o disco local). `Accept-Ranges: bytes` obligatorio para seek.

### Cover art

| Endpoint | Propósito |
|---|---|
| `GET /api/v1/covers/album/{id}?size=small\|medium\|large` | Tres tamaños de carátula |

El response es la imagen directa (JPEG/PNG) con cache-control
adecuado. Size `small` = 300×300, `medium` = 800×800, `large` = 2000×2000.

### Reporte de progreso

| Endpoint | Propósito |
|---|---|
| `POST /api/v1/playback/progress` | `{ track_id, position_ms, completed }` |

Idempotente: si el mismo progreso se reporta dos veces, solo se guarda uno.
La app mobile lo encola cuando offline y flushea en batches al reconectar.

### Metadata de dispositivo

| Endpoint | Propósito |
|---|---|
| `GET /api/v1/devices/me` | Info del dispositivo que hace la request |
| `PATCH /api/v1/devices/me` | Update de metadata (nombre, push token) |

## Auth — decisión

Hoy el backend acepta tanto `X-Pairing-Token` como `Authorization: Bearer ...`
(según `services/pairing.py:131-180`). Para mobile, **usar bearer**. El
`X-Pairing-Token` legacy se mantiene para compatibilidad con tv-library-client.

## Plan

1. **Fase 1** (pairing + sync): implementar `/api/v1/sync/changes` + `/api/v1/sync/full`
   + `/api/v1/covers/album/{id}` en una rama `feature/mobile-sync-api` del
   backend MM. PR contra develop.
2. **Fase 2** (streaming): `/api/v1/tracks/{id}/stream` con autenticación bearer.
3. **Fase 3** (progress): `/api/v1/playback/progress` con batch endpoint.
