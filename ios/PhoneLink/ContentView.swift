import SwiftUI

struct ContentView: View {
    @EnvironmentObject var coordinator: LinkCoordinator

    var body: some View {
        NavigationStack {
            List {
                Section("Link") {
                    LabeledContent("Server", value: "ws://172.20.10.1:8777")
                    LabeledContent("Head units connected", value: "\(coordinator.clientCount)")
                }

                Section("Now Playing (from head unit)") {
                    Text(coordinator.lastTrack)
                    if let image = coordinator.lastArtwork {
                        HStack {
                            Spacer()
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: 240, maxHeight: 240)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Spacer()
                        }
                        LabeledContent("Artwork source", value: coordinator.lastArtworkSource)
                    }
                }

                Section("GPS stream") {
                    Text(coordinator.lastGps)
                        .font(.system(.body, design: .monospaced))
                }

                Section {
                    Text("Keep Personal Hotspot ON and the head unit joined to it. "
                         + "Location \u{201C}Always\u{201D} permission keeps the link alive while locked.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("PhoneLink")
        }
    }
}
