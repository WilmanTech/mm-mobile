# MusicManager Mobile (mm-mobile)

Cliente móvil multiplataforma (Android + iOS) para [MusicManager](https://github.com/WilmanTech/MusicManager).

Acceso completo a tu biblioteca musical, con descargas para reproducción offline. UX estilo Spotify/Apple Music con el tema oscuro y acento amarillo de MusicManager.

> **Estado**: Fase 0 — bootstrap. No hay pairing ni reproducción todavía. Ver [docs/ROADMAP.md](docs/ROADMAP.md).

## Stack

- **Kotlin Multiplatform** (KMP 2.0) — código compartido en `shared/`
- **Android** — Jetpack Compose + Material 3 + Media3/ExoPlayer + Hilt
- **iOS** — SwiftUI + AVPlayer
- **Networking** — Ktor client (OkHttp en Android, Darwin en iOS)
- **Storage** — SQLDelight (mismo .sqm en ambas plataformas) + archivos locales
- **Offline-first** — metadata cacheada siempre, audio descargable por álbum/playlist/track

## Estructura

```
mm-mobile/
├── shared/                # Módulo KMP (commonMain, androidMain, iosMain)
├── android/               # App Android nativa (Compose)
├── ios/                   # App iOS nativa (SwiftUI)
├── docs/                  # Arquitectura, API, guías
└── .github/workflows/     # CI
```

## Quickstart

### Prerrequisitos

- JDK 21 (Temurin o Homebrew `openjdk@21`)
- Xcode 16+ (iOS 16+ deployment target)
- Android Studio Koala+ o Gradle 8.x CLI
- Android SDK 34

### Build

```bash
# Compilar módulo KMP (Android)
./gradlew :shared:assembleDebug

# Compilar app Android (APK debug)
./gradlew :android:assembleDebug

# Compilar framework iOS
./gradlew :shared:linkDebugFrameworkIosX64 :shared:linkDebugFrameworkIosArm64
```

### Tests

```bash
# Tests KMP (common + JVM)
./gradlew :shared:allTests

# Tests Android
./gradlew :android:testDebugUnitTest
```

## Temas

| Token | Color | Uso |
|---|---|---|
| `bg_base` | `#121212` | Fondo de pantallas |
| `bg_sidebar` | `#181818` | Sidebars, bottom nav |
| `bg_card` | `#282828` | Cards, listas |
| `accent_primary` | `#FFD700` | CTAs, focus, badges |
| `accent_hover` | `#E5C100` | Hover state |
| `text_primary` | `#FFFFFF` | Texto principal |
| `text_secondary` | `#B3B3B3` | Texto secundario |
| `text_disabled` | `#535353` | Estados deshabilitados |

## Licencia

MIT
