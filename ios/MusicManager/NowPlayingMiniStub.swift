import SwiftUI

/// Placeholder for the Android `NowPlayingMini` bar — Phase 4.A.5.
///
/// On Android the mini-player is rendered above the bottom-nav via
/// `NowPlayingMiniViewModel` (observes a `PlayerTrigger.state`
/// StateFlow → projects to `NowPlayingMiniUi.Hidden | Active`). It
/// hides itself when `PlayerState.Idle`.
///
/// On iOS we don't have a player layer yet (Phase 4.B will wire
/// AVPlayer via a KMP `PlayerTrigger` actual-impl), so this bar is
/// permanently hidden — `MainTabView` simply does not insert it. This
/// file exists to keep the layout structure aligned with Android and
/// to give the eventual iOS player wiring an obvious home.
///
/// When the iOS player lands, the pattern is identical to Android:
///   - Subscribe to a long-lived `PlayerTrigger.state`
///     (`StateFlow<PlayerState>` via the KMP bridge).
///   - Project each sealed subclass (`PlayerStatePlaying`,
///     `PlayerStatePaused`, …) to a Swift-side enum.
///   - Render `MiniBar(...)` above the bottom-nav; hide when Idle.
///
/// **Why no visible stub now**: reviewers on `MM_VISUAL_REVIEW=1`
/// would read a static placeholder as "the player is broken" and
/// file it as a bug. Better to ship no bar at all than fake one.
struct NowPlayingMiniStub: View {
    var body: some View {
        EmptyView()
    }
}
