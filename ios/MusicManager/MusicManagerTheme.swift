import SwiftUI

/// MusicManager brand palette, lifted from
/// `android/src/main/res/values/colors.xml` so the iOS and Android clients
/// share the same tokens. The Asset Catalog (`Assets.xcassets/MM*`) holds
/// the sRGB values; this file exposes them as `Color.MM*` static members
/// so SwiftUI views can use them like first-class types.
///
/// Why a separate file instead of inlining `Color("MMAccentPrimary")` at
/// every call site: typos in catalog names fail silently and produce
/// magenta-on-black warnings at runtime — a typed wrapper catches that
/// at compile time.
extension Color {

    /// Brand accent (the yellow used by the desktop for primary CTAs).
    /// Android equivalent: `@color/accent_primary` = `#FFD700`.
    static let mmAccentPrimary = Color("MMAccentPrimary")

    /// Hover/pressed state for the accent (slightly desaturated yellow).
    /// Android equivalent: `@color/accent_hover` = `#E5C100`.
    static let mmAccentHover = Color("MMAccentHover")

    /// Window/canvas background (the dark grey the desktop uses for the
    /// root container).
    /// Android equivalent: `@color/bg_base` = `#121212`.
    static let mmBgBase = Color("MMBgBase")

    /// Sidebar / nav rail background.
    /// Android equivalent: `@color/bg_sidebar` = `#181818`.
    static let mmBgSidebar = Color("MMBgSidebar")

    /// Card / row background (used inside lists and forms).
    /// Android equivalent: `@color/bg_card` = `#282828`.
    static let mmBgCard = Color("MMBgCard")

    /// Primary text colour (titles, key labels).
    /// Android equivalent: `@color/text_primary` = `#FFFFFF`.
    static let mmTextPrimary = Color("MMTextPrimary")

    /// Secondary text colour (captions, helper text).
    /// Android equivalent: `@color/text_secondary` = `#B3B3B3`.
    static let mmTextSecondary = Color("MMTextSecondary")

    /// Disabled / placeholder text colour.
    /// Android equivalent: `@color/text_disabled` = `#535353`.
    static let mmTextDisabled = Color("MMTextDisabled")
}

/// Semantic colour aliases — views should prefer these over the raw palette
/// members so a future dark/light split (or brand refresh) stays a
/// one-file change.
extension ShapeStyle where Self == Color {

    /// Background for the root canvas (NavigationStack container).
    static var mmBackground: Color { .mmBgBase }

    /// Background for grouped content (Form sections).
    static var mmSurface: Color { .mmBgCard }

    /// Foreground for titles and primary labels.
    static var mmPrimaryText: Color { .mmTextPrimary }

    /// Foreground for captions, helper text, and the like.
    static var mmSecondaryText: Color { .mmTextSecondary }
}

/// MusicManager visual identity, wrapped in a SwiftUI entry point so the
/// app can adopt the dark-only palette everywhere with one line at the
/// root scene.
///
/// Usage in `MusicManagerApp.swift`:
/// ```swift
/// RootView()
///     .preferredColorScheme(.dark)
///     .tint(.mmAccentPrimary)
/// ```
///
/// We do NOT use a custom `ColorScheme` because the desktop and the
/// Android client are both dark-only by design — adding light support
/// would force a design audit we don't want to commit to yet.
enum MusicManagerTheme {

    /// The single tint colour applied via `.tint(...)` so SwiftUI buttons,
    /// toggles, and selection highlights all pick up the brand yellow
    /// without per-view `.tint(...)` overrides.
    static let accent: Color = .mmAccentPrimary

    /// Default corner radius for cards and grouped surfaces.
    static let cornerRadius: CGFloat = 16

    /// Default glass tint used on iOS 26 surfaces. Mirrors the
    /// `.regular` glass variant but pre-tinted with the brand accent.
    @available(iOS 26.0, *)
    static let glass: Glass = .regular.tint(.mmAccentPrimary.opacity(0.18))
}
