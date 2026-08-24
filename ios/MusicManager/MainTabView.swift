import SwiftUI
import MusicManagerShared

/// Four-tab bottom-nav scaffold.
///
/// **Phase 3.B+ (mini player positioning, 2026-08-14)** — the mini
/// player used to live in a `.safeAreaInset(edge: .bottom)` slot on
/// the `TabView`, but that pushed the entire TabView content (which
/// already includes the system tab bar at the bottom) up by 64pt. The
/// result on iPhone 11: the tab bar's icon row overlapped with the
/// mini player's row, making the tab bar tap targets collide with
/// the mini player chrome and the search bar getting visually
/// cropped. The user reported "se solapa con posición de cajonera
/// navegación" — exactly this overlap.
///
/// **Fix**: this view is now a `ZStack` of three layers:
///
///   1. `TabView` filling the whole surface (its internal tab bar
///      handles safe-area bottom inset correctly).
///   2. `VStack { Spacer; NowPlayingMiniView }` aligned to the
///      bottom, sitting on top of the TabView. Spacer pushes the
///      mini player to the bottom; the mini player is a regular
///      SwiftUI view that sizes itself naturally (`frame(height:
///      isVisible ? 64 : 0)`).
///   3. The tab content inside each tab owns its own bottom safe
///      area via `.safeAreaPadding(.bottom, miniPlayerHeight)` when
///      the player is active. Today the tabs just stop their scroll
///      content with `.padding(.bottom, miniPlayerHeight)` from
///      `LibraryScreen` / `SearchView` — this stops the last row of
///      a list from being hidden under the mini player.
///
/// **Trade-off**: this approach doesn't follow the Apple Music
/// pattern exactly (Apple Music uses a real safeAreaInset on the
/// tabs so the mini player "is part of the surface"). We chose
/// manual positioning because SwiftUI's safeAreaInset on iOS 26
/// interacts badly with `TabView`'s own safe-area handling — the
/// tab bar's height was being added to the inset, doubling the
/// bottom padding. Manual overlay gives pixel-perfect control.
struct MainTabView: View {

    let graph: LibraryEntry.Graph
    @ObservedObject var player: AvPlayerEngine
    @EnvironmentObject private var coordinator: AppCoordinator

    enum Tab: Hashable {
        case home, search, library, settings
    }

    @State private var selection: Tab = .home

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selection) {
                HomeView(graph: graph, player: player)
                    .tabItem {
                        Label("Inicio", systemImage: "house.fill")
                    }
                    .tag(Tab.home)

                SearchView(graph: graph, player: player)
                    .tabItem {
                        Label("Buscar", systemImage: "magnifyingglass")
                    }
                    .tag(Tab.search)

                LibraryScreen(graph: graph, player: player)
                    .tabItem {
                        Label("Biblioteca", systemImage: "music.note.list")
                    }
                    .tag(Tab.library)

                SettingsView(graph: graph, player: player)
                    .tabItem {
                        Label("Ajustes", systemImage: "gearshape.fill")
                    }
                    .tag(Tab.settings)
            }
            .tint(Color.mmAccentPrimary)
            .modifier(MMTabBarGlass())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive, action: coordinator.unpair) {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                    }
                    .tint(Color.mmAccentPrimary)
                }
            }
            #if DEBUG
            .simultaneousGesture(TapGesture(count: 3).onEnded {
                coordinator.unpair()
            })
            #endif

            // Mini player overlay — sits on top of the TabView,
            // NOT inside it, so it doesn't push the tab bar up. The
            // library / search / home screens are responsible for
            // reserving their own bottom padding equal to
            // `miniPlayerHeight` (or `0` when idle) so their scroll
            // content doesn't get hidden.
            NowPlayingMiniView(engine: player, graph: graph)
                .padding(.bottom, 49) // TabView's standard tab bar height.
        }
        .background(Color.mmBackground)
    }
}

/// Brand-tinted glass material wrapped around `TabView`.
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