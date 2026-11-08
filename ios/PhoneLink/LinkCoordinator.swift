import Foundation
import CoreLocation
import Network
import UIKit

/// Glues LinkServer + LocationStreamer + ArtworkService, and publishes
/// state for the status UI.
@MainActor
final class LinkCoordinator: ObservableObject {

    @Published var clientCount = 0
    @Published var lastTrack = "waiting for head unit…"
    @Published var lastArtwork: UIImage?
    @Published var lastArtworkSource = "—"
    @Published var lastGps = "waiting for fix…"

    private let server = LinkServer(port: 8777)
    private let location = LocationStreamer()
    private let artwork = ArtworkService()

    /// Discard lookups that finish after a newer track arrived.
    private var latestSeq = -1

    init() {
        server.onClientCountChanged = { [weak self] count in
            Task { @MainActor in self?.clientCount = count }
        }

        server.onTrack = { [weak self] track, conn in
            guard let self else { return }
            Task { @MainActor in
                self.latestSeq = track.seq
                self.lastTrack = "\(track.artist) — \(track.title)"
                await self.lookupAndSend(track: track, to: conn)
            }
        }

        location.onLocation = { [weak self] loc in
            guard let self else { return }
            if let json = LocationStreamer.gpsJSON(for: loc) {
                self.server.broadcastText(json)
            }
            Task { @MainActor in
                let kmh = loc.speed >= 0 ? String(format: "%.0f km/h", loc.speed * 3.6) : "—"
                self.lastGps = String(format: "%.5f, %.5f  %@", loc.coordinate.latitude,
                                      loc.coordinate.longitude, kmh)
            }
        }

        server.start()
        location.start()
    }

    private func lookupAndSend(track: LinkServer.Track, to conn: NWConnection) async {
        let result = await artwork.lookup(artist: track.artist, title: track.title)
        // Stale guard: a newer track arrived while we were looking up.
        guard track.seq == latestSeq else { return }
        server.sendArtwork(seq: track.seq, jpeg: result?.jpeg,
                           source: result?.source, to: conn)
        if let jpeg = result?.jpeg {
            lastArtwork = UIImage(data: jpeg)
            lastArtworkSource = result?.source ?? "online"
        } else {
            lastArtwork = nil
            lastArtworkSource = "not found"
        }
    }
}
