# KMP + CocoaPods gotchas

El módulo `:shared` produce frameworks iOS vía CocoaPods para que la app
SwiftUI nativa los consuma.

## Setup

- `shared/build.gradle.kts` declara `cocoapods { ... }` con nombre de pod `MMShared`
- Xcode abre `ios/mm-mobile.xcworkspace` (generado por `pod install`)
- El framework queda en `shared/build/cocoapods/framework/MMShared.framework`

## Build local

```bash
cd ios && pod install
xcodebuild -workspace ios/mm-mobile.xcworkspace \
           -scheme mm-mobile \
           -configuration Debug \
           -sdk iphonesimulator \
           -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

## Gotchas

- **JDK**: Xcode + Gradle + KMP requiere JDK 17 (NO 21). Configurar
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17` antes de `pod install` si tienes
  múltiples JDKs. SDKMAN recomendado.
- **Gradle wrapper vs system Gradle**: usar `./gradlew` siempre, no `gradle`
  global, para evitar mismatch de versiones.
- **Cache limpia**: si Xcode no encuentra el framework tras cambios,
  `rm -rf shared/build ios/Pods ios/Podfile.lock && pod install`.
- **Versioning**: cuando bumpeas `version = "x.y.z"` en `cocoapods {}`,
  también bumpear en `Podfile` y `podspec` auto-generado.

## Documentación oficial

- https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods.html
- https://kotlinlang.org/docs/multiplatform/multiplatform-ios-dependencies.html
