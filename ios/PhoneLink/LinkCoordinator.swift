import Foundation
import CoreLocation
import Network
import UIKit

/// Glues LinkServer + BLELinkServer + LocationStreamer + ArtworkService,
/// and publishes state for the status UI. GPS is broadcast on both
/// transports; artwork replies go back on the transport that sent the track.
@MainActor
final class LinkCoordinator: ObservableObject {

    @Published var clientCount = 0
    @Published var bleSubscriberCount = 0
    @Published var lastTrack = "waiting for head unit…"
    @Published var lastArtwork: UIImage?
    @Published var lastArtworkSource = "—"
    @Published var lastGps = "waiting for fix…"
    @Published var gpsStatus = "—"

    private let server = LinkServer(port: 8777)
    private let ble = BLELinkServer()
    private let location = LocationStreamer()
    private let artwork = ArtworkService()

    /// Discard lookups that finish after a newer track arrived.
    private var latestSeq = -1

    /// Which transport asked for the current track's artwork.
    private enum Reply { case webSocket(NWConnection), ble }

    init() {
        server.onClientCountChanged = { [weak self] count in
            Task { @MainActor in self?.clientCount = count }
        }
        ble.onSubscriberCountChanged = { [weak self] count in
            Task { @MainActor in self?.bleSubscriberCount = count }
        }

        server.onTrack = { [weak self] track, conn in
            guard let self else { return }
            Task { @MainActor in
                await self.handleTrack(track, replyVia: .webSocket(conn))
            }
        }
        ble.onTrack = { [weak self] track in
            guard let self else { return }
            Task { @MainActor in
                await self.handleTrack(track, replyVia: .ble)
            }
        }

        location.onLocation = { [weak self] loc in
            guard let self else { return }
            if let json = LocationStreamer.gpsJSON(for: loc) {
                self.server.broadcastText(json)
                self.ble.sendGps(json)
            }
            Task { @MainActor in
                let kmh = loc.speed >= 0 ? String(format: "%.0f km/h", loc.speed * 3.6) : "—"
                self.lastGps = String(format: "%.5f, %.5f  %@", loc.coordinate.latitude,
                                      loc.coordinate.longitude, kmh)
            }
        }

        location.onStatus = { [weak self] status in
            Task { @MainActor in self?.gpsStatus = status }
        }

        server.start()
        ble.start()
        location.start()
    }

    private func handleTrack(_ track: LinkServer.Track, replyVia reply: Reply) async {
        latestSeq = track.seq
        lastTrack = "\(track.artist) — \(track.title)"
        let result = await artwork.lookup(artist: track.artist, title: track.title)
        // Stale guard: a newer track arrived while we were looking up.
        guard track.seq == latestSeq else { return }
        switch reply {
        case .webSocket(let conn):
            server.sendArtwork(seq: track.seq, jpeg: result?.jpeg,
                               source: result?.source, to: conn)
        case .ble:
            // BLE moves ~5-15 KB/s: shrink artwork to ~300 px for transfer.
            let small = result.map { Self.downscaleForBle($0.jpeg) ?? $0.jpeg }
            ble.sendArtwork(seq: track.seq, jpeg: small, source: result?.source)
        }
        if let jpeg = result?.jpeg {
            lastArtwork = UIImage(data: jpeg)
            lastArtworkSource = result?.source ?? "online"
        } else {
            lastArtwork = nil
            lastArtworkSource = "not found"
        }
    }

    private static func downscaleForBle(_ jpeg: Data, maxSide: CGFloat = 300) -> Data? {
        guard let image = UIImage(data: jpeg) else { return nil }
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        guard scale < 1 else { return jpeg }
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let resized = UIGraphicsImageRenderer(size: size).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return resized.jpegData(compressionQuality: 0.7)
    }
}
