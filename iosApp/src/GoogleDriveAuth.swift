import AuthenticationServices
import CryptoKit
import Foundation
import KotlinModules
import Security
import UIKit

/// Google sign-in and Drive access tokens without the Google Sign-In SDK: OAuth 2.0 for native apps (RFC 8252) with PKCE (RFC 7636),
/// shown in ASWebAuthenticationSession. The account picker is Google's own page.
///
/// Configuration lives in Info.plist: `GIDClientID` (iOS OAuth client, `NNN-xxx.apps.googleusercontent.com`) and its reversed form
/// `com.googleusercontent.apps.NNN-xxx` in CFBundleURLTypes. With a missing or placeholder client ID the provider stays registered,
/// so the Backup screen behaves like Android without a matching OAuth client: Connect reports "Could not sign in".
///
/// Only the refresh token and account metadata (`sub`, email) are persisted, in one Keychain item readable after first unlock
/// (background tasks need it). The access token is kept in memory until shortly before it expires and is dropped on
/// invalidateAccessToken, signOut and a new consent, like the Android Identity API's token cache. Tokens are never logged.
nonisolated final class GoogleDriveAuth: NSObject, GoogleAuthBridge, @unchecked Sendable {
    private let config: GoogleOAuthConfig?
    private let keychain = GrantKeychain()
    private let http: URLSession
    private let lock = NSLock()
    private var cached: CachedToken?
    private var consentInProgress = false
    private var presenter: ConsentPresenter?

    override init() {
        config = GoogleOAuthConfig.fromMainBundle()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 40
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        http = URLSession(configuration: configuration)
        super.init()
        keychain.forgetGrantFromPreviousInstall()
        if config == nil { NSLog("GoogleDriveAuth: GIDClientID is missing or a placeholder; Google Drive backup is not configured") }
    }

    var isConfigured: Bool { config != nil }

    // MARK: GoogleAuthBridge

    func currentAccount(callback: any GoogleAccountCallback) {
        let account: GoogleAccount? = switch keychain.load() {
        case .success(let grant?): GoogleAccount(id: grant.sub, displayLabel: grant.email)
        case .success(nil), .failure: nil
        }
        callback.onResult(account: account)
    }

    func fetchAccessToken(callback: any AccessTokenCallback) {
        let grant: StoredGrant
        switch keychain.load() {
        case .success(let stored?): grant = stored
        case .success(nil): return callback.onResult(result: AccessTokenResult.NotSignedIn.shared)
        case .failure(let status): return callback.onResult(result: failure(retryable: true, "keychain_\(status.code)"))
        }
        guard let config else { return callback.onResult(result: failure(retryable: false, "not_configured")) }
        if let token = cachedToken(for: grant) { return callback.onResult(result: AccessTokenResult.Token(accessToken: token)) }

        let body = [
            "grant_type": "refresh_token",
            "client_id": config.clientID,
            "refresh_token": grant.refreshToken,
        ]
        postForm(GoogleOAuthConfig.tokenEndpoint, body) { [self] outcome in
            switch outcome {
            case .transport(let diagnostic):
                callback.onResult(result: failure(retryable: true, diagnostic))
            case .http(let status, let json):
                callback.onResult(result: refreshFailure(status: status, error: json?["error"] as? String))
            case .ok(let json):
                guard let token = TokenResponse(json), let accessToken = token.accessToken else {
                    return callback.onResult(result: failure(retryable: false, "token_parse"))
                }
                if let scopes = token.scopes, !scopes.contains(GoogleOAuthConfig.driveAppDataScope) {
                    return callback.onResult(result: AccessTokenResult.NeedsUserAction.shared)
                }
                if let rotated = token.refreshToken, rotated != grant.refreshToken {
                    keychain.replaceRefreshToken(of: grant, with: rotated)
                }
                remember(accessToken, expiresIn: token.expiresIn, for: grant.refreshToken)
                callback.onResult(result: AccessTokenResult.Token(accessToken: accessToken))
            }
        }
    }

    func invalidateAccessToken(accessToken: String, callback: any AuthCompletionCallback) {
        lock.withLock { if cached?.accessToken == accessToken { cached = nil } }
        callback.onComplete()
    }

    func requestConsent(callback: any ConsentCallback) {
        guard let config else { return callback.onResult(result_: ConsentResult.Failure(retryable: false, diagnostic: "not_configured")) }
        let started = lock.withLock {
            if consentInProgress { return false }
            consentInProgress = true
            return true
        }
        guard started else {
            return callback.onResult(result_: ConsentResult.Failure(retryable: true, diagnostic: "consent_in_progress"))
        }
        let finish: (ConsentResult) -> Void = { [self] result in
            lock.withLock {
                consentInProgress = false
                presenter = nil
            }
            if let failure = result as? ConsentResult.Failure {
                NSLog("GoogleDriveAuth: consent failed: %@ (retryable=%d)", failure.diagnostic, failure.retryable ? 1 : 0)
            }
            callback.onResult(result_: result)
        }

        let pkce = PKCE()
        let state = randomURLSafe(bytes: 16)
        guard let url = config.authorizationURL(challenge: pkce.challenge, state: state) else {
            return finish(ConsentResult.Failure(retryable: false, diagnostic: "authorize_url"))
        }
        DispatchQueue.main.async { [self] in
            let presenter = ConsentPresenter()
            lock.withLock { self.presenter = presenter }
            let started = presenter.start(url: url, callbackScheme: config.redirectScheme) { [self] callbackURL, error in
                if let error {
                    return finish(Self.consentFailure(error))
                }
                guard let callbackURL, let items = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems else {
                    return finish(ConsentResult.Failure(retryable: false, diagnostic: "callback_url"))
                }
                let query = Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
                guard query["state"] == state else {
                    return finish(ConsentResult.Failure(retryable: false, diagnostic: "state_mismatch"))
                }
                if let error = query["error"] {
                    // access_denied: the user declined on Google's consent page, which is a cancel, as on Android.
                    return finish(error == "access_denied" ? ConsentResult.Cancelled.shared : ConsentResult.Failure(retryable: false, diagnostic: "authorize_\(error)"))
                }
                guard let code = query["code"], !code.isEmpty else {
                    return finish(ConsentResult.Failure(retryable: false, diagnostic: "callback_no_code"))
                }
                exchange(code: code, verifier: pkce.verifier, config: config, finish: finish)
            }
            if !started { finish(ConsentResult.Failure(retryable: true, diagnostic: "session_not_started")) }
        }
    }

    /// Local sign-out only, like Android: revoking would withdraw the consent for the whole Cloud project (shared with Android) and needs network.
    func signOut(callback: any AuthCompletionCallback) {
        lock.withLock { cached = nil }
        keychain.delete()
        callback.onComplete()
    }

    // MARK: Consent steps

    private func exchange(code: String, verifier: String, config: GoogleOAuthConfig, finish: @escaping (ConsentResult) -> Void) {
        let body = [
            "grant_type": "authorization_code",
            "code": code,
            "code_verifier": verifier,
            "client_id": config.clientID,
            "redirect_uri": config.redirectURI,
        ]
        postForm(GoogleOAuthConfig.tokenEndpoint, body) { [self] outcome in
            let token: TokenResponse
            switch outcome {
            case .transport(let diagnostic):
                return finish(ConsentResult.Failure(retryable: true, diagnostic: diagnostic))
            case .http(let status, let json):
                let error = json?["error"] as? String ?? "http_\(status)"
                return finish(ConsentResult.Failure(retryable: Self.isTransient(status), diagnostic: "exchange_\(error)"))
            case .ok(let json):
                guard let parsed = TokenResponse(json) else { return finish(ConsentResult.Failure(retryable: false, diagnostic: "token_parse")) }
                token = parsed
            }
            // Granular consent lets the user untick Drive; without it there is nothing to back up to, which Android also treats as a cancel.
            guard token.scopes?.contains(GoogleOAuthConfig.driveAppDataScope) == true else { return finish(ConsentResult.Cancelled.shared) }
            guard let accessToken = token.accessToken else { return finish(ConsentResult.Failure(retryable: false, diagnostic: "no_token")) }
            guard let refreshToken = token.refreshToken else { return finish(ConsentResult.Failure(retryable: false, diagnostic: "no_refresh_token")) }
            fetchUserInfo(accessToken) { [self] info in
                switch info {
                case .failure(let retryable, let diagnostic):
                    finish(ConsentResult.Failure(retryable: retryable, diagnostic: diagnostic))
                case .success(let sub, let email):
                    let grant = StoredGrant(sub: sub, email: email, refreshToken: refreshToken)
                    if let status = keychain.save(grant) {
                        return finish(ConsentResult.Failure(retryable: true, diagnostic: "keychain_write_\(status.code)"))
                    }
                    lock.withLock { cached = nil }
                    remember(accessToken, expiresIn: token.expiresIn, for: refreshToken)
                    finish(ConsentResult.Granted(account: GoogleAccount(id: sub, displayLabel: email)))
                }
            }
        }
    }

    private enum UserInfo {
        case success(sub: String, email: String)
        case failure(retryable: Bool, diagnostic: String)
    }

    /// The stable account ID (`sub`) and email come from OpenID userinfo with the fresh token, as on Android.
    private func fetchUserInfo(_ accessToken: String, completion: @escaping (UserInfo) -> Void) {
        var request = URLRequest(url: GoogleOAuthConfig.userInfoEndpoint)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        send(request) { outcome in
            switch outcome {
            case .transport(let diagnostic):
                completion(.failure(retryable: true, diagnostic: "userinfo_\(diagnostic)"))
            case .http(let status, _):
                completion(.failure(retryable: Self.isTransient(status), diagnostic: "userinfo_http_\(status)"))
            case .ok(let json):
                guard let sub = (json["sub"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !sub.isEmpty else {
                    return completion(.failure(retryable: false, diagnostic: "userinfo_no_sub"))
                }
                guard let email = (json["email"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !email.isEmpty else {
                    return completion(.failure(retryable: false, diagnostic: "userinfo_no_email"))
                }
                completion(.success(sub: sub, email: email))
            }
        }
    }

    // MARK: Error mapping (same user-facing states as AndroidGoogleAuth)

    private static func consentFailure(_ error: Error) -> ConsentResult {
        if let session = error as? ASWebAuthenticationSessionError {
            switch session.code {
            case .canceledLogin: return ConsentResult.Cancelled.shared
            case .presentationContextNotProvided, .presentationContextInvalid:
                return ConsentResult.Failure(retryable: true, diagnostic: "session_presentation")
            @unknown default:
                return ConsentResult.Failure(retryable: false, diagnostic: "session_\(session.code.rawValue)")
            }
        }
        let ns = error as NSError
        return ConsentResult.Failure(retryable: ns.domain == NSURLErrorDomain, diagnostic: "session_\(ns.domain)_\(ns.code)")
    }

    /// invalid_grant means the refresh token was revoked or expired (password change, consent withdrawn, Testing mode's 7 days): the user must reconnect.
    private func refreshFailure(status: Int, error: String?) -> AccessTokenResult {
        if error == "invalid_grant" {
            lock.withLock { cached = nil }
            return AccessTokenResult.NeedsUserAction.shared
        }
        return failure(retryable: Self.isTransient(status), "token_\(error ?? "http_\(status)")")
    }

    private static func isTransient(_ status: Int) -> Bool { status == 408 || status == 429 || (500...599).contains(status) }

    private func failure(retryable: Bool, _ diagnostic: String) -> AccessTokenResult {
        NSLog("GoogleDriveAuth: access token: %@ (retryable=%d)", diagnostic, retryable ? 1 : 0)
        return AccessTokenResult.Failure(retryable: retryable, diagnostic: diagnostic)
    }

    // MARK: Access token cache

    private struct CachedToken {
        let accessToken: String
        let refreshToken: String
        let expiresAt: Date
    }

    private func cachedToken(for grant: StoredGrant) -> String? {
        lock.withLock {
            guard let cached, cached.refreshToken == grant.refreshToken, cached.expiresAt > Date() else { return nil }
            return cached.accessToken
        }
    }

    private func remember(_ accessToken: String, expiresIn: TimeInterval?, for refreshToken: String) {
        // Keep a 5-minute margin so a backup never starts with a token that expires midway.
        let lifetime = (expiresIn ?? 0) - 300
        guard lifetime > 0 else { return }
        lock.withLock { cached = CachedToken(accessToken: accessToken, refreshToken: refreshToken, expiresAt: Date().addingTimeInterval(lifetime)) }
    }

    // MARK: HTTP

    private enum HTTPOutcome {
        case ok([String: Any])
        case http(Int, [String: Any]?)
        case transport(String)
    }

    private func postForm(_ url: URL, _ fields: [String: String], completion: @escaping (HTTPOutcome) -> Void) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = formEncode(fields).data(using: .utf8)
        send(request, completion: completion)
    }

    private func send(_ request: URLRequest, completion: @escaping (HTTPOutcome) -> Void) {
        http.dataTask(with: request) { data, response, error in
            if let error {
                let ns = error as NSError
                return completion(.transport("network_\(ns.code)"))
            }
            guard let status = (response as? HTTPURLResponse)?.statusCode else { return completion(.transport("no_response")) }
            let json = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            if (200...299).contains(status) {
                guard let json else { return completion(.http(status, nil)) }
                completion(.ok(json))
            } else {
                completion(.http(status, json))
            }
        }.resume()
    }
}

// MARK: - Configuration

nonisolated struct GoogleOAuthConfig: Sendable {
    static let authorizationEndpoint = URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!
    static let tokenEndpoint = URL(string: "https://oauth2.googleapis.com/token")!
    static let userInfoEndpoint = URL(string: "https://openidconnect.googleapis.com/v1/userinfo")!
    static let driveAppDataScope = "https://www.googleapis.com/auth/drive.appdata"
    static let scopes = [driveAppDataScope, "openid", "email"]

    private static let clientSuffix = ".apps.googleusercontent.com"
    private static let schemePrefix = "com.googleusercontent.apps."

    let clientID: String
    let redirectScheme: String
    var redirectURI: String { "\(redirectScheme):/oauth2redirect" }

    /// Accepts only a real-looking iOS client ID (`digits-lowercase.apps.googleusercontent.com`); the shipped placeholder is rejected.
    static func fromMainBundle() -> GoogleOAuthConfig? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String else { return nil }
        let clientID = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clientID.hasSuffix(clientSuffix) else { return nil }
        let prefix = String(clientID.dropLast(clientSuffix.count))
        guard prefix.range(of: "^[0-9]+-[a-z0-9]+$", options: .regularExpression) != nil else { return nil }
        let scheme = schemePrefix + prefix
        if !registeredURLSchemes().contains(scheme) {
            NSLog("GoogleDriveAuth: URL scheme for GIDClientID is not in CFBundleURLTypes; add it before shipping")
        }
        return GoogleOAuthConfig(clientID: clientID, redirectScheme: scheme)
    }

    private static func registeredURLSchemes() -> Set<String> {
        let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        return Set(types.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] })
    }

    func authorizationURL(challenge: String, state: String) -> URL? {
        var components = URLComponents(url: Self.authorizationEndpoint, resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: Self.scopes.joined(separator: " ")),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            // select_account: always show Google's account chooser, like Android's SELECT_ACCOUNT prompt; any account can be picked.
            // consent: Google issues a refresh token on every connect, also when this account already granted access before (reinstall, reconnect).
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "select_account consent"),
        ]
        return components?.url
    }
}

nonisolated private struct TokenResponse {
    let accessToken: String?
    let refreshToken: String?
    let expiresIn: TimeInterval?
    let scopes: Set<String>?

    init?(_ json: [String: Any]) {
        accessToken = (json["access_token"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        refreshToken = (json["refresh_token"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue
        scopes = (json["scope"] as? String).map { Set($0.split(separator: " ").map(String.init)) }
        if accessToken == nil && refreshToken == nil { return nil }
    }
}

// MARK: - PKCE

nonisolated private struct PKCE {
    let verifier: String
    let challenge: String

    init() {
        verifier = randomURLSafe(bytes: 32)
        challenge = base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }
}

nonisolated private func randomURLSafe(bytes count: Int) -> String {
    var bytes = [UInt8](repeating: 0, count: count)
    let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
    precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
    return base64URL(Data(bytes))
}

nonisolated private func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

nonisolated private func formEncode(_ fields: [String: String]) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return fields
        .sorted { $0.key < $1.key }
        .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
        .joined(separator: "&")
}

// MARK: - Presentation

/// Owns the ASWebAuthenticationSession for one consent and anchors it to the app's key window.
@MainActor
private final class ConsentPresenter: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func start(url: URL, callbackScheme: String, completion: @escaping @Sendable (URL?, Error?) -> Void) -> Bool {
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
            self?.session = nil
            completion(callbackURL, error)
        }
        session.presentationContextProvider = self
        // Shared Safari cookies let the user pick an account already signed in on the device; Google's page still lets them add any other.
        session.prefersEphemeralWebBrowserSession = false
        self.session = session
        let started = session.start()
        if !started { self.session = nil }
        return started
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            let active = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
            if let window = active?.windows.first(where: \.isKeyWindow) ?? active?.windows.first { return window }
            if let active { return UIWindow(windowScene: active) }
            return ASPresentationAnchor()
        }
    }
}

// MARK: - Keychain

nonisolated struct StoredGrant: Codable, Sendable {
    let sub: String
    let email: String
    let refreshToken: String
}

nonisolated struct KeychainStatus: Error {
    let code: OSStatus
}

/// One generic-password item, this device only, readable after the first unlock so the background backup can refresh tokens.
nonisolated private final class GrantKeychain: @unchecked Sendable {
    private let service = "app.servicecalendar.google-drive"
    private let account = "grant"
    private let lock = NSLock()
    private static let installMarker = "GoogleDriveAuth.keychainBelongsToInstall"

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    /// Keychain items survive uninstall, app data does not. A fresh install must start signed out, as on Android, so a grant left by a previous install is deleted.
    func forgetGrantFromPreviousInstall() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: Self.installMarker) else { return }
        lock.withLock { _ = SecItemDelete(baseQuery as CFDictionary) }
        defaults.set(true, forKey: Self.installMarker)
    }

    func load() -> Result<StoredGrant?, KeychainStatus> {
        lock.withLock { loadLocked() }
    }

    private func loadLocked() -> Result<StoredGrant?, KeychainStatus> {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data, let grant = try? JSONDecoder().decode(StoredGrant.self, from: data),
                  !grant.sub.isEmpty, !grant.email.isEmpty, !grant.refreshToken.isEmpty else { return .success(nil) }
            return .success(grant)
        case errSecItemNotFound:
            return .success(nil)
        default:
            return .failure(KeychainStatus(code: status))
        }
    }

    /// Returns nil on success.
    func save(_ grant: StoredGrant) -> KeychainStatus? {
        lock.withLock { saveLocked(grant) }
    }

    private func saveLocked(_ grant: StoredGrant) -> KeychainStatus? {
        guard let data = try? JSONEncoder().encode(grant) else { return KeychainStatus(code: errSecParam) }
        _ = SecItemDelete(baseQuery as CFDictionary)
        var item = baseQuery
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        return status == errSecSuccess ? nil : KeychainStatus(code: status)
    }

    /// Writes a rotated refresh token only if the stored grant is still the one that was refreshed, so a sign-out or new consent in between wins.
    func replaceRefreshToken(of grant: StoredGrant, with refreshToken: String) {
        lock.withLock {
            guard case .success(let current?) = loadLocked(), current.sub == grant.sub, current.refreshToken == grant.refreshToken else { return }
            _ = saveLocked(StoredGrant(sub: grant.sub, email: grant.email, refreshToken: refreshToken))
        }
    }

    func delete() {
        lock.withLock { _ = SecItemDelete(baseQuery as CFDictionary) }
    }
}
