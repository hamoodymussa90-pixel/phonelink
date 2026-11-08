import SwiftUI

@main
struct PhoneLinkApp: App {
    @StateObject private var coordinator = LinkCoordinator()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(coordinator)
        }
    }
}
