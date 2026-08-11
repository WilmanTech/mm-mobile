import SwiftUI

@main
struct MusicManagerApp: App {

    @StateObject private var coordinator = AppCoordinator()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(coordinator)
                .onOpenURL { url in
                    coordinator.handleDeepLink(url)
                }
        }
    }
}
