import Foundation

/// Artwork lookup chain: in-memory cache -> YouTube Music (InnerTube) -> iTunes.
///
/// YouTube Music has no official public API. We call the same internal
/// InnerTube endpoint music.youtube.com itself uses (no API key, same approach
/// as the ytmusicapi library). It matches YT-exclusive uploads and remixes
/// that iTunes doesn't have. If parsing ever breaks (it's an unofficial API),
/// the iTunes Search API fallback keeps artwork working.
actor ArtworkService {

    struct Result {
        let jpeg: Data
        let source: String  // "ytmusic" | "itunes" | "cache"
    }

    private var cache: [String: Result] = [:]
    private var cacheOrder: [String] = []   // LRU, max 20
    private let session = URLSession(configuration: .ephemeral)

    func lookup(artist: String, title: String) async -> Result? {
        let key = "\(artist)|\(title)".lowercased()
        if let hit = cache[key] {
            return Result(jpeg: hit.jpeg, source: "cache")
        }
        var result: Result?
        if let jpeg = try? await ytMusicLookup(query: "\(artist) \(title)") {
            result = Result(jpeg: jpeg, source: "ytmusic")
        } else if let jpeg = try? await itunesLookup(artist: artist, title: title) {
            result = Result(jpeg: jpeg, source: "itunes")
        }
        if let result {
            cache[key] = result
            cacheOrder.append(key)
            if cacheOrder.count > 20 {
                cache.removeValue(forKey: cacheOrder.removeFirst())
            }
        }
        return result
    }

    // MARK: - YouTube Music (InnerTube)

    private func ytMusicLookup(query: String) async throws -> Data? {
        var request = URLRequest(
            url: URL(string: "https://music.youtube.com/youtubei/v1/search?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("https://music.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("https://music.youtube.com/", forHTTPHeaderField: "Referer")
        let body: [String: Any] = [
            "context": [
                "client": [
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240101.00.00",
                    "hl": "en"
                ]
            ],
            "query": query,
            // "songs" filter param (same constant ytmusicapi sends).
            "params": "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              // First song row in the results.
              let item = Self.findFirst(key: "musicResponsiveListItemRenderer", in: json),
              let thumbs = Self.findFirst(key: "thumbnails", in: item) as? [[String: Any]],
              var urlString = (thumbs.last?["url"] as? String)
        else { return nil }

        // googleusercontent thumbnails accept arbitrary size suffixes.
        if urlString.contains("googleusercontent.com"),
           let range = urlString.range(of: "=w", options: .backwards) {
            urlString = String(urlString[..<range.lowerBound]) + "=w600-h600-l90-rj"
        }
        guard let url = URL(string: urlString) else { return nil }
        let (img, imgResp) = try await session.data(from: url)
        guard (imgResp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return img
    }

    /// Depth-first search for the first value under `key` anywhere in a JSON tree.
    private static func findFirst(key: String, in object: Any) -> Any? {
        if let dict = object as? [String: Any] {
            if let value = dict[key] { return value }
            // "contents" first keeps us inside the ordered results list.
            for k in dict.keys.sorted(by: { $0 == "contents" && $1 != "contents" }) {
                if let found = findFirst(key: key, in: dict[k]!) { return found }
            }
        } else if let array = object as? [Any] {
            for element in array {
                if let found = findFirst(key: key, in: element) { return found }
            }
        }
        return nil
    }

    // MARK: - iTunes fallback

    private func itunesLookup(artist: String, title: String) async throws -> Data? {
        var components = URLComponents(string: "https://itunes.apple.com/search")!
        components.queryItems = [
            .init(name: "term", value: "\(artist) \(title)"),
            .init(name: "entity", value: "song"),
            .init(name: "limit", value: "1")
        ]
        let (data, _) = try await session.data(from: components.url!)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = json["results"] as? [[String: Any]],
              let artUrl = results.first?["artworkUrl100"] as? String,
              let url = URL(string: artUrl.replacingOccurrences(of: "100x100", with: "600x600"))
        else { return nil }
        let (img, response) = try await session.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return img
    }
}
