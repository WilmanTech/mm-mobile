# AGENTS.md — mm-mobile

> Guía para agentes (humanos o AI) que trabajen en este repo. Describe
> arquitectura, comandos, convenciones y gotchas específicas del proyecto.

## Producto en una línea

App estilo Spotify/Apple Music para Android + iOS que reproduce una library
musical organizada en el desktop (`WilmanTech/MusicManager`) o directamente
desde un share SMB/NAS, con descarga local para uso offline. **NO** hace
identificación acústica — el matching es por nombre de archivo + tags.

## Branching convention

- **`main`** — releases estables. Recibe merges desde `develop` cuando una
  iteración está lista para release. Protegida: no se commitea directamente.
- **`develop`** — integración. Aquí llegan los merges de `feature/*` y
  el trabajo nuevo que todavía no es release. Rama default del repo.
- **`feature/*`** — trabajo en curso. Se mergean a `develop` vía PR.

Workflow típico:
```bash
git checkout develop && git pull --rebase origin develop
git checkout -b feature/mi-feature
# ... trabajo ...
git push -u origin feature/mi-feature
# abrir PR contra develop
```

Para promover `develop` a release:
```bash
git checkout main && git pull
git merge --no-ff develop
git tag -a vX.Y.Z -m "release notes"
git push origin main --tags
```

## Stack

- **Lenguaje compartido**: Kotlin 2.0.21 (Multiplatform)
- **Android UI**: Jetpack Compose + Material 3
- **iOS UI**: SwiftUI
- **Networking**: Ktor 2.3.13 (cliente HTTP portable)
- **DB**: SQLDelight 2.0.2 (mismo `.sqm` para ambas plataformas)
- **DI**: Koin 4.0 (common) / Hilt 2.52 (Android, sólo donde Hilt es más idiomático)
- **Android media**: Media3/ExoPlayer 1.4.1
- **iOS media**: AVPlayer (nativo, expuesto vía `expect/actual`)
- **Android SMB**: smbj 0.13.0 (puro Java, sin JNI)
- **iOS SMB**: `libsmb2` vía CocoaPods (C library + wrapper Swift)
- **TagLib metadata**: `aTantalum` (Android) / `TagLib` C++ wrapper (iOS)
- **Build**: Gradle Kotlin DSL, JDK 21, Android SDK 34, Xcode 16+

## Arquitectura

### Capas (módulo `shared/`)

```
domain/model/             # entidades (Track, Album, Artist, Playlist, PlaylistTrack)
domain/repository/        # interfaces (LibraryRepository, DownloadRepository, ...)
domain/source/            # LibrarySource abstraction (MMBACKEND, SMB)
network/                  # Ktor client, endpoints, auth bearer, AuthStorage expect/actual
data/                     # SQLDelight queries, mappers dominio ↔ DB, SyncCoordinator
sync/                     # pull incremental desde backend
pairing/                  # flujo de emparejamiento QR + bearer token persistido
connectivity/             # ConnectivityMonitor (Android ConnectivityManager / iOS NWPathMonitor)
source/mmbackend/         # LibrarySource implementation contra MusicManager REST
source/smb/               # LibrarySource implementation contra SMB share (Android smbj / iOS libsmb2)
download/                 # cola persistente de descargas (WorkManager Android / BGTaskScheduler iOS)
player/                   # abstracción del player + sync de progreso (futuro, Fase 2)
metadata/                 # lectura de tags con TagLib (futuro, Fase 3)
```

### `LibrarySource` — abstracción clave

Tanto el backend MM como un share SMB son "fuentes de library" idénticas
para la UI:

```kotlin
interface LibrarySource {
    val id: String
    val displayName: String
    val kind: SourceKind  // MMBACKEND, SMB
    suspend fun connect(creds: Credentials): Result<Unit>
    suspend fun listArtists(): Result<List<Artist>>
    suspend fun listAlbums(artistId: String): Result<List<Album>>
    suspend fun listTracks(albumId: String): Result<List<Track>>
    suspend fun streamUrl(trackId: String): Result<String>
    suspend fun cover(albumId: String, size: CoverSize): Result<ByteArray>
    suspend fun disconnect()
}
```

La library "recordada" se persiste cifrada en SQLDelight (`library_source`
tabla) con id + kind + config serializada (host, share, user, pass cifrado
con `EncryptedSharedPreferences`/`Keychain`).

**El pairing MM es una `LibrarySource` con kind=MMBACKEND. Un SMB share es
otra con kind=SMB. La app puede tener varias a la vez y cambiar entre
ellas desde un selector.**

### Patrón de offline-first

1. **Metadata siempre cacheada**: cualquier recurso que la app muestra vive en
   SQLDelight. La fuente de verdad online es el backend (o el SMB share),
   pero la app **nunca** muestra estado vacío si tiene datos cacheados.
2. **Sync incremental**: cada tabla tiene `synced_at`. Sync solo pide deltas
   `WHERE updated_at > ?` (ISO 8601). Sólo aplica a MMBACKEND; SMB hace
   scan recursivo.
3. **Audio descargado opcional**: trigger explícito del usuario. Estado por
   track en `track.download_state`.
4. **Conflict resolution**: newer-wins (server `updated_at` > local `synced_at`).

### UX online vs offline

- `ConnectivityMonitor` emite `Offline | Syncing | Connected(deviceName)`
- Banner persistente en cada pantalla: "Modo offline · N canciones de {source}"
- Búsqueda online-fallback-local
- Settings → About muestra última sincronización por fuente

## Comandos

### Build

```bash
./gradlew :shared:assembleDebug                          # KMP Android
./gradlew :android:assembleDebug                         # APK
./gradlew :shared:linkDebugFrameworkIosX64               # iOS framework
./gradlew :shared:linkDebugFrameworkIosArm64             # iOS framework (device)
```

### Tests

```bash
./gradlew :shared:jvmTest           # KMP common + JVM unit tests (20 tests)
./gradlew :android:testDebugUnitTest # Android JVM tests
./gradlew :shared:iosX64Test        # KMP iOS tests (simulator)
```

### AVD E2E pairing

```bash
# (one-time) setup AVD and build APK — see docs/avd-e2e/README.md
# (each run) verify backend running on :8765 then:
python3 scripts/run-mm-pairing-e2e.py
```

Validates the `/api/pairing/{start,status,confirm}` round-trip end-to-end
from a real Android process (headless Google TV AVD on Apple Silicon).

### Lint / format

```bash
./gradlew :shared:detekt            # Kotlin lint
./gradlew :android:lintDebug        # Android lint
```

## Convenciones

- **Naming**: paquetes en `com.wtm.musicmanager.*`. Tests en sufijo `Test`.
- **JSON**: snake_case (vía `@SerialName`). La capa de red mapea a camelCase.
- **Flows**: repositorios exponen `Flow<Resource>` donde `Resource = Loading | Data | Error`.
- **Errors**: `sealed class MError { ... }` en `domain/error/`, mapeado desde HTTP status.
- **Strings**: solo en inglés en código; l10n i18n en `feature/<X>/<platform>Main/res/values-<lang>/`.
- **Tests**: Kotest BDD style (`fun \`something works\`() = ...`). Property-based para parsers/serializers.

## Phase 2 — LibraryScreen end-to-end

This phase wires the read-side library layer that Phase 1.1 left dangling.
The Phase 1.1 commit delivered SyncCoordinator + the SQLDelight schema
with upsert queries, but the UI had no way to read those tables back.
Phase 2 closes that loop.

### Added

- `LibraryQuery` (search/artistId/albumId/limit/offset with `init` validation)
- `LibraryRepository` interface (`Flow<List<Track>>`, `Flow<List<Artist>>`, point lookups)
- `SqlDelightLibraryRepository` impl using `asFlow().mapToList(Dispatchers.IO)`
- SQLDelight read queries in `Queries.sq`:
  - `selectAllTracks`, `selectTracksByArtist`, `searchTracks`
  - `selectTrackCount`, `selectArtistById`, `selectAlbumById`
- `LibraryViewModel` (`@HiltViewModel`): debounced query (150ms) + `flatMapLatest`
  + `combine` with artists → `LibraryUiState` (`StateFlow`)
- `LibraryScreen` Compose: search bar + artist chips + `LazyColumn` of tracks +
  three empty/loading states
- `PairingScreen` real: host/port `OutlinedTextField` + start button + code
  display + countdown timer
- `RootViewModel` + `MusicManagerRoot` gate: shows `PairingScreen` until
  `PairingState.Paired`, then the bottom-nav scaffold
- Hilt graph (`LibraryModule` + `AuthStorageModule`): provides
  `MusicManagerDatabase` / `LibraryRepository` / `SyncUpsertQueries` /
  `MusicManagerApi` / `HttpClient` / `PairingRepository` / `TokenStore`
- `SqlDelightSyncUpsertQueries`: production impl of the `SyncUpsertQueries`
  facade. Maps `domain.model.*` → SQLDelight row shape, converts ISO 8601
  serverTime → epoch-ms `Long`
- `DatabaseDriverFactory` (`expect/actual`): Android actual wires
  `AndroidSqliteDriver` directly from `LibraryModule` to avoid the
  `initAndroidDriver()` race with Hilt's graph

### iOS note

As of Phase 2 we are **dropping the KMP shared module for the UI** and
moving to a 100% native Swift client. The `shared/` module is retained
for now (PairingRepository / SyncCoordinator / LibraryRepository are
used by the Android app), but new UI work goes into a separate
`wtm-music-ios/` Swift project. Once the iOS app has its own native
ports of the pairing + library flows, `shared/` will be deleted.

## Gotchas

- **KMP & CocoaPods**: el módulo `:shared` está configurado como framework de
  CocoaPods. Cualquier cambio en `shared/build.gradle.kts` requiere
  `pod install` en `ios/`. Ver [docs/kmp-cocoapods.md](docs/kmp-cocoapods.md).
- **SQLDelight migrations**: los `.sqm` van en `shared/src/commonMain/sqldelight/`.
  Para nuevas versiones, archivo `2.sqm`, `3.sqm`, etc. El generator produce
  las clases; commitearlas en `shared/build/generated/...` NO es necesario.
- **Multiplatform Hilt vs Koin**: Hilt solo se usa en `:android` (UI bindings,
  WorkManager). `:shared` usa Koin para mantener paridad iOS.
- **Connectivity**: Android necesita `ACCESS_NETWORK_STATE`. iOS no requiere
  permiso para `NWPathMonitor`.
- **Background downloads en iOS**: BGTaskScheduler es restrictivo (~30s CPU,
  sin garantía de tiempo). Para colas largas, implementar foreground service
  equivalente en Android con `WorkManager` y `setForegroundAsync`.
- **smbj 0.13.0 (Android)**: tres trampas conocidas — `typealias SmbjConfig`
  para evitar colisión con `com.hierynomus.smbj.SmbConfig`, imports de
  `SMB2ShareAccess/SMB2CreateOptions/SMB2CreateDisposition` desde
  `com.hierynomus.mssmb2.*` (NO `com.hierynomus.smbj.share.*`), y
  `SMB2ShareAccess.ALL` es ya un `Set`, no necesita `setOf()`.
- **TagLib bindings KMP**: en Android usar `aTantalum` (puro Java), en iOS
  `TagLib` C++ vía CocoaPods + C-interop. NO usar `jaudiotagger` por su
  API verbosa y dependencias opcionales.

## Backend MusicManager (MMBACKEND source)

Esta app consume la API REST del backend `WilmanTech/MusicManager`:

### Endpoints de pairing (no autenticados)
- `POST /api/pairing/start` — body: `{device_type?: "mobile"|"tv"}`. Response: `{session_id, token, code, expires_in}`. El token es la futura bearer credential.
- `GET /api/pairing/qr?session_id=&host=&port=` — devuelve PNG con URL `vm://pair?session=...&token=...&code=...&host=...&port=...`. **OJO: el QR usa scheme `vm://` por compatibilidad con VideoManager** — la app parsea el session param regardless.
- `POST /api/pairing/confirm` — body: `{session_id, code, device_name?, device_type?}`. Response: `{status: "confirmed", token, device_name, paired_at: float}`.
- `GET /api/pairing/status?session_id=` — response: `{exists, session_id, confirmed: bool, device_name, ..., expired: bool, revoked: bool}`. **Polling**: 2s, hasta 5 min.

### Endpoints autenticados (bearer)
- `Authorization: Bearer <token>` (header). NO `X-Pairing-Token` — eso era incorrecto, el backend lo rechaza.
- `GET /api/v1/whoami` — smoke test del token.
- `GET /api/v1/ping` — ping ligero, 401 si token inválido. Usado en cold-start para detectar stale tokens.
- `GET /api/v1/sync/full` — bootstrap snapshot.
- `GET /api/v1/sync/changes?since=<ISO 8601>` — incremental deltas. `since` es **query param**, NO header.

### Contrato de Sync (envelope unificado)
```json
{
  "server_time": "2026-08-09T12:34:56Z",
  "since": "2026-08-09T12:00:00Z",
  "artists": [...],
  "albums": [...],
  "tracks": [...],
  "playlists": [...],
  "playlist_tracks": [...]
}
```
`updated_at` y `created_at` son **ISO 8601 con 'Z'** (`YYYY-MM-DDTHH:MM:SSZ`),
NO epoch_ms. El backend reformatea los TEXT timestamps de SQLite via
`_row_to_iso()` antes de emitir.

### Auditar endpoints disponibles
Ver `WilmanTech/MusicManager/backend/main.py` para la lista completa.
Endpoints útiles no usados todavía:
- `GET /api/library/*` (artists, albums, tracks, playlists) — read directo,
  alternativa a sync/full
- `POST /api/library/favorites/{track_id}` — toggle favorite (sí existe)
- `GET /api/library/recent/tracks`, `/api/library/recent/albums`
- `GET /api/stream/{track_id}` — streaming con Range (para playback)
- `GET /api/library/covers/{cover_path:path}` — cover art
- `POST /api/import/{files,url,youtube,torrent}` — backend Download Manager

## Roadmap (no incluido en este commit)

| Fase | Alcance | Estado |
|---|---|---|
| 0 | Bootstrap KMP, DTOs reales contra backend, AVD E2E pairing | **✓ entregado** |
| 1 | LibrarySource abstraction + SMB client (Android smbj, iOS libsmb2) | próximo |
| 2 | Download queue persistente + reproducción offline | futuro |
| 3 | Backend Download Manager + TagLib bindings + AddDownloadScreen | futuro |
| 4 | Tablet layouts, drag-to-download, sidebar | add-on posterior |

## Tests de smoke

- Pairing: scanear QR del backend → `connected` en ConnectivityMonitor. **✓ AVD E2E valida**
- Sync inicial: catálogo vacío → populate desde backend → UI muestra datos
- Offline: toggle airplane mode → banner aparece, listas siguen funcionando
- Download: marcar álbum → ver progreso → toggle airplane → reproducir offline
