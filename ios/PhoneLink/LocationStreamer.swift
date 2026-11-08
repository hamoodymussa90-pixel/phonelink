import Foundation
import CoreLocation

/// Streams location at ~1 Hz. The "location" background mode keeps the app
/// (and therefore the WebSocket server, BLE notifications + artwork lookups)
/// alive while the iPhone is locked.
///
/// Liveness fixes:
///  - requests temporary FULL accuracy if the user granted only approximate
///    location (approximate = km-level error, looks like "not live GPS")
///  - re-sends the last fix every 1.5 s while stationary so the dashboard
///    stream never freezes
///  - exposes a human-readable status so the UI can show WHY GPS is degraded
final class LocationStreamer: NSObject, CLLocationManagerDelegate {

    var onLocation: ((CLLocation) -> Void)?
    var onStatus: ((String) -> Void)?

    private let manager = CLLocationManager()
    private var lastLocation: CLLocation?
    private var lastSent: Date = .distantPast
    private var keepAliveTimer: Timer?

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()

        // Stationary keep-alive: CoreLocation stops delivering when not moving;
        // re-send the last fix so the head unit keeps getting fresh frames.
        keepAliveTimer?.invalidate()
        keepAliveTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            guard let self, let location = self.lastLocation,
                  Date().timeIntervalSince(self.lastSent) >= 1.5 else { return }
            self.lastSent = Date()
            self.onLocation?(location)
        }
        publishStatus()
    }

    func stop() {
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            // Background updates require the "location" UIBackgroundMode;
            // the visible indicator makes iOS keep the stream running reliably.
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
            if manager.accuracyAuthorization == .reducedAccuracy {
                // User granted only approximate location — ask for full
                // accuracy for this session (purpose key in Info.plist).
                manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "Navigation")
            }
            manager.startUpdatingLocation()
        default:
            break
        }
        publishStatus()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastLocation = location
        // Throttle to 1 Hz.
        guard Date().timeIntervalSince(lastSent) >= 1.0 else { return }
        lastSent = Date()
        onLocation?(location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("LocationStreamer: \(error.localizedDescription)")
        publishStatus()
    }

    private func publishStatus() {
        let auth: String
        switch manager.authorizationStatus {
        case .authorizedAlways: auth = "Always"
        case .authorizedWhenInUse: auth = "While Using ⚠️ (set to Always for lock-screen GPS)"
        case .denied: auth = "DENIED — enable in Settings > PhoneLink > Location"
        case .restricted: auth = "Restricted"
        case .notDetermined: auth = "Not requested yet"
        @unknown default: auth = "Unknown"
        }
        let precise = manager.accuracyAuthorization == .fullAccuracy
            ? "precise" : "APPROXIMATE ⚠️ — enable Precise Location in Settings"
        onStatus?("\(auth), \(precise)")
    }

    /// Protocol-v1 gps JSON for a CLLocation.
    static func gpsJSON(for location: CLLocation) -> String? {
        let payload: [String: Any] = [
            "type": "gps",
            "ts": Date().timeIntervalSince1970,
            "fixTs": location.timestamp.timeIntervalSince1970,
            "lat": location.coordinate.latitude,
            "lon": location.coordinate.longitude,
            "speedMps": location.speed >= 0 ? location.speed : -1,
            "headingDeg": location.course >= 0 ? location.course : -1,
            "altitudeM": location.altitude,
            "hAccM": location.horizontalAccuracy
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
