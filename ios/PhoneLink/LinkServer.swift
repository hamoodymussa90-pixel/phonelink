import Foundation
import Network

/// WebSocket server the head unit connects to (protocol v1).
/// The iPhone is the hotspot, so the head unit reaches us at the
/// gateway IP (normally 172.20.10.1) on `port`.
///
/// Incoming:  {"type":"track",...}, {"type":"ping"}, {"type":"pong"}
/// Outgoing:  {"type":"gps",...} broadcast, {"type":"artwork",...} header
///            followed by ONE binary frame (JPEG), app-level ping every 10 s.
final class LinkServer {

    struct Track {
        let seq: Int
        let title: String
        let artist: String
        let album: String?
    }

    var onTrack: ((Track, NWConnection) -> Void)?
    var onClientCountChanged: ((Int) -> Void)?

    private let port: NWEndpoint.Port
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private let queue = DispatchQueue(label: "linkserver")
    private var pingTimer: DispatchSourceTimer?

    init(port: UInt16 = 8777) {
        self.port = NWEndpoint.Port(rawValue: port)!
    }

    func start() {
        let params = NWParameters.tcp
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        do {
            listener = try NWListener(using: params, on: port)
        } catch {
            print("LinkServer: failed to listen on \(port): \(error)")
            return
        }
        listener?.newConnectionHandler = { [weak self] conn in self?.setup(conn) }
        listener?.start(queue: queue)

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 10, repeating: 10)
        timer.setEventHandler { [weak self] in self?.broadcastText(#"{"type":"ping"}"#) }
        timer.resume()
        pingTimer = timer
    }

    func stop() {
        pingTimer?.cancel()
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
        listener?.cancel()
    }

    // MARK: - Sending

    func broadcastText(_ text: String) {
        queue.async { [self] in
            connections.values.forEach { sendText(text, to: $0) }
        }
    }

    func sendText(_ text: String, to conn: NWConnection) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        conn.send(content: text.data(using: .utf8), contentContext: context,
                  completion: .contentProcessed { _ in })
    }

    func sendBinary(_ data: Data, to conn: NWConnection) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        conn.send(content: data, contentContext: context,
                  completion: .contentProcessed { _ in })
    }

    /// Artwork reply: JSON header, then (when ok) one binary JPEG frame.
    func sendArtwork(seq: Int, jpeg: Data?, source: String?, to conn: NWConnection) {
        var header: [String: Any] = ["type": "artwork", "seq": seq]
        if let jpeg {
            header["status"] = "ok"
            header["format"] = "image/jpeg"
            header["bytes"] = jpeg.count
            header["source"] = source ?? "online"
        } else {
            header["status"] = "not_found"
        }
        guard let headerData = try? JSONSerialization.data(withJSONObject: header),
              let headerText = String(data: headerData, encoding: .utf8) else { return }
        queue.async { [self] in
            sendText(headerText, to: conn)
            if let jpeg { sendBinary(jpeg, to: conn) }
        }
    }

    // MARK: - Receiving

    private func setup(_ conn: NWConnection) {
        let id = ObjectIdentifier(conn)
        connections[id] = conn
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.remove(id)
            default:
                break
            }
        }
        receiveLoop(on: conn)
        conn.start(queue: queue)
        onClientCountChanged?(connections.count)
    }

    private func remove(_ id: ObjectIdentifier) {
        connections.removeValue(forKey: id)?.cancel()
        onClientCountChanged?(connections.count)
    }

    private func receiveLoop(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, context, _, error in
            guard let self else { return }
            guard error == nil, let context else {
                self.remove(ObjectIdentifier(conn))
                return
            }
            if let data,
               let meta = context.protocolMetadata(definition: NWProtocolWebSocket.definition)
                            as? NWProtocolWebSocket.Metadata,
               meta.opcode == .text {
                self.handleText(data, from: conn)
            }
            self.receiveLoop(on: conn)
        }
    }

    private func handleText(_ data: Data, from conn: NWConnection) {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return }
        switch type {
        case "track":
            let track = Track(
                seq: obj["seq"] as? Int ?? -1,
                title: obj["title"] as? String ?? "",
                artist: obj["artist"] as? String ?? "",
                album: obj["album"] as? String
            )
            onTrack?(track, conn)
        case "ping":
            sendText(#"{"type":"pong"}"#, to: conn)
        case "pong":
            break
        default:
            break
        }
    }
}
