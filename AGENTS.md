# AGENTS.md — mm-mobile

> Guía para agentes (humanos o AI) que trabajen en este repo. Describe
> arquitectura, comandos, convenciones y gotchas específicas del proyecto.

## Branching convention

- **`main`** — releases estables. Recibe merges desde `develop` cuando una
  iteración está lista para release. Protegida: no se commitea directamente.
- **`develop`** — integración. Aquí llegan los merges de `feature/*` y
  el trabajo nuevo que todavía no es release. Rama default del repo.
- **`feature/*`** — trabajo en curso (e.g. `feat/parity-avd-e2e`,
  `feat/vm-mirror-mm-sync`). Se mergean a `develop` vía PR.

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
- **Build**: Gradle Kotlin DSL, JDK 21, Android SDK 34, Xcode 16+

## Arquitectura

### Capas (módulo `shared/`)

```
domain/model/         # entidades del dominio (Track, Album, Artist, Playlist)
domain/repository/    # interfaces (LibraryRepository, DownloadRepository, PlayerRepository)
network/              # Ktor client, endpoints, auth bearer
data/                 # SQLDelight queries, mappers dominio ↔ DB, sync coordinator
download/             # cola persistente de descargas (WorkManager en Android, BGTaskScheduler en iOS)
player/               # abstracción del player + sync de progreso
connectivity/         # ConnectivityMonitor (Android ConnectivityManager / iOS NWPathMonitor)
sync/                 # pull incremental desde backend, deltas por timestamp
pairing/              # flujo de emparejamiento QR + host:port manual
```

### Patrón de offline-first

1. **Metadata siempre cacheada**: cualquier recurso que la app muestra vive en
   SQLDelight. La fuente de verdad online es el backend, pero la app **nunca**
   muestra estado vacío si tiene datos cacheados.
2. **Sync incremental**: cada tabla tiene `synced_at`. Sync solo pide deltas
   `WHERE updated_at > ?` (TBD con backend).
3. **Audio descargado opcional**: trigger explícito del usuario. Estado por
   track en `track.download_state`.
4. **Conflict resolution**: newer-wins (server `updated_at` > local `synced_at`).

### UX online vs offline

- `ConnectivityMonitor` emite `Offline | Syncing | Connected(serverLabel)`
- Banner persistente en cada pantalla: "Modo offline · N canciones disponibles"
- Búsqueda online-fallback-local
- Settings → About muestra última sincronización

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
./gradlew :shared:allTests          # KMP common + JVM tests
./gradlew :android:testDebugUnitTest # Android JVM tests
./gradlew :shared:iosX64Test        # KMP iOS tests (simulator)
```

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

## Backend MM

Esta app consume la API REST del backend `WilmanTech/MusicManager`:

- **Pairing**: `/api/pairing/*` (start, qr, confirm, status, devices)
- **Library**: `/api/library/{artists,albums,tracks,playlists}/...`
- **v1 endpoints**: `/api/v1/*` (protegidos con `X-Pairing-Token` o bearer)

Auditoría de gaps (endpoints pendientes de implementar en backend): ver
[docs/backend-gaps.md](docs/backend-gaps.md).

## Sincronización con MusicManager desktop

El backend MM es la única fuente de verdad. La app móvil y el desktop
comparten la misma DB SQLite (`music_library.db`). El sync E2E:

1. App móvil hace `GET /api/library/sync?since=<timestamp>` (a definir)
2. Backend responde con deltas: `{artists: [...], albums: [...], tracks: [...]}`
3. App upserta en local SQLDelight

## Tests de smoke

- Pairing: scanear QR del backend → `connected` en ConnectivityMonitor
- Sync inicial: catálogo vacío → populate desde backend → UI muestra datos
- Offline: toggle airplane mode → banner aparece, listas siguen funcionando
- Download: marcar álbum → ver progreso → toggle airplane → reproducir offline
