import Foundation
import CoreLocation

/// Streams location at ~1 Hz. The "location" background mode keeps the app
/// (and therefore the WebSocket server + artwork lookups) alive while the
/// iPhone is locked.
final class LocationStreamer: NSObject, CLLocationManagerDelegate {

    var onLocation: ((CLLocation) -> Void)?

    private let manager = CLLocationManager()
    private var lastSent: Date = .distantPast

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            // Background updates require the "location" UIBackgroundMode
            // and (for lock-screen streaming) Always authorization.
            manager.allowsBackgroundLocationUpdates = true
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        // Throttle to 1 Hz.
        guard Date().timeIntervalSince(lastSent) >= 1.0 else { return }
        lastSent = Date()
        onLocation?(location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("LocationStreamer: \(error.localizedDescription)")
    }

    /// Protocol-v1 gps JSON for a CLLocation.
    static func gpsJSON(for location: CLLocation) -> String? {
        let payload: [String: Any] = [
            "type": "gps",
            "ts": location.timestamp.timeIntervalSince1970,
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
