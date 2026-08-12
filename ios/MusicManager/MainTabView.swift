import SwiftUI
import MusicManagerShared

/// Four-tab bottom-nav scaffold — Phase 4.A.5 iOS parity with
/// Android's `MusicManagerRoot` `NavigationBar { ... TopLevelTab.values() ... }`.
///
/// Tabs:
///   - Inicio    → `HomeView`
///   - Buscar    → `SearchView`
///   - Biblioteca → `LibraryScreen`
///   - Ajustes   → `SettingsView`
///
/// **Why a custom `tabViewStyle` instead of `TabView` directly**:
/// `TabView` on iOS 26 has a hardened default appearance that doesn't
/// match the brand palette — we apply a custom `background(Color.mmBgSidebar)`
/// and brand-tinted selection so the bottom-nav feels like the
/// desktop's dark grey sidebar, not a system-default list.
///
/// **Glass material** (Phase 4.A.2): on iOS 26 we apply `.glassEffect(...)`
/// to the bar surface; on iOS < 26 we fall back to a flat
/// `Color.mmBgSidebar` clip. Both branches share the same `Material`.
///
/// **Phase 3.B addition**: a `[NowPlayingMiniView]` sits **above** the
/// bottom-tab bar whenever the player engine has anything loaded.
/// It disappears when `EngineState` is `.idle` or `.error`, matching
/// the Android `NowPlayingMini` behavior. The `player` engine is
/// created once in `RootView` and shared here.
struct MainTabView: View {

    let graph: LibraryEntry.Graph
    @ObservedObject var player: AvPlayerEngine
    @EnvironmentObject private var coordinator: AppCoordinator

    enum Tab: Hashable {
        case home, search, library, settings
    }

    @State private var selection: Tab = .home

    var body: some View {
        VStack(spacing: 0) {
            // Phase 3.B: compact now-playing bar that appears above the
            // tab bar whenever the player has loaded a track. The bar
            // is `EmptyView` when the engine is idle/error so there's
            // no visible gap.
            NowPlayingMiniView(engine: player)

            TabView(selection: $selection) {
                HomeView(graph: graph)
                    .tabItem {
                        Label("Inicio", systemImage: "house.fill")
                    }
                    .tag(Tab.home)

                SearchView(graph: graph)
                    .tabItem {
                        Label("Buscar", systemImage: "magnifyingglass")
                    }
                    .tag(Tab.search)

                LibraryScreen(graph: graph)
                    .tabItem {
                        Label("Biblioteca", systemImage: "music.note.list")
                    }
                    .tag(Tab.library)

                SettingsView(graph: graph)
                    .tabItem {
                        Label("Ajustes", systemImage: "gearshape.fill")
                    }
                    .tag(Tab.settings)
            }
            .tint(Color.mmAccentPrimary)
            .modifier(MMTabBarGlass())
            .toolbar {
                // Unpair remains a corner action — same affordance
                // `PairedScreen` had.
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive, action: coordinator.unpair) {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                    }
                    .tint(Color.mmAccentPrimary)
                }
            }
            // Persistent unpair gesture: 3-finger tap is a developer-only
            // shortcut that mirrors the way Android's debug builds let
            // QA bypass long-press menus. Disabled in Release.
            #if DEBUG
            .simultaneousGesture(TapGesture(count: 3).onEnded {
                coordinator.unpair()
            })
            #endif
        }
        .background(Color.mmBackground)
    }
}

/// Brand-tinted glass material wrapped around `TabView`.
///
/// `TabView`'s internal layout doesn't surface a `barBackground`
/// modifier directly — `.toolbarBackground(...)` is the supported
/// hook on iOS 26 and falls back gracefully on older OS.
private struct MMTabBarGlass: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .toolbarBackground(.mmSurface.opacity(0.85), for: .tabBar)
                .toolbarBackground(.visible, for: .tabBar)
        } else {
            content
                .toolbarBackground(Color.mmBgSidebar, for: .tabBar)
                .toolbarBackground(.visible, for: .tabBar)
        }
    }
}