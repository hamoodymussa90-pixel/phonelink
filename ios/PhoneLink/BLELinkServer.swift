import CoreBluetooth
import Foundation

/// BLE transport (protocol v1 over GATT). The iPhone is a BLE *peripheral*
/// advertising a custom service; the Android head unit is the central.
///
/// Characteristics:
///  - GPS      (notify): one JSON gps message per notification (~130 B, 1 Hz)
///  - TRACK    (write):  head unit writes the JSON track message
///  - ART_META (notify): JSON artwork header + "chunks" count
///  - ART_DATA (notify): binary chunks; 4-byte header = seq:UInt16BE, index:UInt16BE
///
/// Note: BLE moves ~5-15 KB/s, so callers should downscale artwork (~300 px)
/// before passing it here. Also: when the app is backgrounded, iOS moves the
/// advertised service UUID into the "overflow area", which Android scanners
/// can't filter on — have the head unit connect while the app is foregrounded
/// once; an established connection survives backgrounding/lock.
final class BLELinkServer: NSObject {

    static let serviceUUID = CBUUID(string: "7E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let gpsUUID     = CBUUID(string: "7E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    static let trackUUID   = CBUUID(string: "7E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    static let artMetaUUID = CBUUID(string: "7E400004-B5A3-F393-E0A9-E50E24DCCA9E")
    static let artDataUUID = CBUUID(string: "7E400005-B5A3-F393-E0A9-E50E24DCCA9E")

    var onTrack: ((LinkServer.Track) -> Void)?
    var onSubscriberCountChanged: ((Int) -> Void)?

    private var manager: CBPeripheralManager!
    private let queue = DispatchQueue(label: "blelink")

    private var gpsChar: CBMutableCharacteristic!
    private var artMetaChar: CBMutableCharacteristic!
    private var artDataChar: CBMutableCharacteristic!

    private var subscribers: Set<CBCentral> = []
    /// Pending notifications; drained as the controller's queue frees up.
    private var outbox: [(CBMutableCharacteristic, Data)] = []

    func start() {
        manager = CBPeripheralManager(delegate: self, queue: queue)
    }

    func stop() {
        queue.async { [self] in
            manager?.stopAdvertising()
            manager?.removeAllServices()
            outbox.removeAll()
        }
    }

    // MARK: - Sending

    /// 1 Hz GPS: fire-and-forget; if the queue is busy we just drop this fix.
    func sendGps(_ json: String) {
        queue.async { [self] in
            guard !subscribers.isEmpty, let data = json.data(using: .utf8) else { return }
            _ = manager.updateValue(data, for: gpsChar, onSubscribedCentrals: nil)
        }
    }

    /// Artwork: meta JSON first, then binary chunks. Pass pre-downscaled JPEG.
    func sendArtwork(seq: Int, jpeg: Data?, source: String?) {
        queue.async { [self] in
            guard !subscribers.isEmpty else { return }

            let chunkPayload = maxChunkPayload()
            var meta: [String: Any] = ["type": "artwork", "seq": seq]
            var chunks: [Data] = []

            if let jpeg {
                let total = (jpeg.count + chunkPayload - 1) / chunkPayload
                meta["status"] = "ok"
                meta["format"] = "image/jpeg"
                meta["bytes"] = jpeg.count
                meta["chunks"] = total
                meta["source"] = source ?? "online"
                for index in 0..<total {
                    let start = index * chunkPayload
                    let end = min(start + chunkPayload, jpeg.count)
                    var chunk = Data(capacity: 4 + end - start)
                    chunk.append(UInt8(seq >> 8 & 0xFF)); chunk.append(UInt8(seq & 0xFF))
                    chunk.append(UInt8(index >> 8 & 0xFF)); chunk.append(UInt8(index & 0xFF))
                    chunk.append(jpeg[start..<end])
                    chunks.append(chunk)
                }
            } else {
                meta["status"] = "not_found"
            }

            guard let metaData = try? JSONSerialization.data(withJSONObject: meta) else { return }
            // Replace any half-sent artwork with the new one.
            outbox.removeAll()
            outbox.append((artMetaChar, metaData))
            chunks.forEach { outbox.append((artDataChar, $0)) }
            drainOutbox()
        }
    }

    private func maxChunkPayload() -> Int {
        let mtu = subscribers.map(\.maximumUpdateValueLength).min() ?? 20
        return max(20, min(mtu, 512)) - 4  // minus our 4-byte chunk header
    }

    private func drainOutbox() {
        while let (characteristic, data) = outbox.first {
            guard manager.updateValue(data, for: characteristic, onSubscribedCentrals: nil) else {
                return // controller queue full; resumes in peripheralManagerIsReady
            }
            outbox.removeFirst()
        }
    }
}

// MARK: - CBPeripheralManagerDelegate

extension BLELinkServer: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }

        gpsChar = CBMutableCharacteristic(
            type: Self.gpsUUID, properties: [.notify], value: nil, permissions: [.readable])
        let trackChar = CBMutableCharacteristic(
            type: Self.trackUUID, properties: [.write, .writeWithoutResponse],
            value: nil, permissions: [.writeable])
        artMetaChar = CBMutableCharacteristic(
            type: Self.artMetaUUID, properties: [.notify], value: nil, permissions: [.readable])
        artDataChar = CBMutableCharacteristic(
            type: Self.artDataUUID, properties: [.notify], value: nil, permissions: [.readable])

        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [gpsChar, trackChar, artMetaChar, artDataChar]
        peripheral.removeAllServices()
        peripheral.add(service)
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: "PhoneLink"
        ])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        subscribers.insert(central)
        onSubscriberCountChanged?(subscribers.count)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribers.remove(central)
        onSubscriberCountChanged?(subscribers.count)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == Self.trackUUID,
               let data = request.value,
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               obj["type"] as? String == "track" {
                let track = LinkServer.Track(
                    seq: obj["seq"] as? Int ?? -1,
                    title: obj["title"] as? String ?? "",
                    artist: obj["artist"] as? String ?? "",
                    album: obj["album"] as? String
                )
                onTrack?(track)
            }
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainOutbox()
    }
}
