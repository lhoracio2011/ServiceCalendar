import BackgroundTasks
import Foundation
import KotlinModules
import Network

/// Daily Drive backup on iOS. Two triggers, both going through the shared BackupCoordinator (which skips a run if the last backup is under 24 h old):
/// - BGProcessingTask `app.servicecalendar.backup` (Info.plist BGTaskSchedulerPermittedIdentifiers + UIBackgroundModes `processing`), earliest 24 h
///   after scheduling, network required. iOS alone decides when it runs (typically while the phone is idle, often overnight); it is not
///   guaranteed daily, is delayed by Low Power Mode, and never runs after the user force-quits the app until the next launch.
///   BGTaskScheduler is unavailable in the Simulator (submit fails with BGTaskSchedulerErrorDomain 1); test on a device. To force a run there, pause in
///   the Xcode debugger and run: e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"app.servicecalendar.backup"]
/// - App open (scene becomes active, at most every 10 minutes, only when online): catches up whenever the background task did not run.
enum BackgroundBackup {
    static let identifier = "app.servicecalendar.backup"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let processing = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(processing)
        }
    }

    /// Schedules only if no request is pending: submit replaces an existing request and would push earliestBeginDate back on every cold start.
    static func scheduleIfNeeded() {
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
            if requests.contains(where: { $0.identifier == identifier }) { return }
            schedule()
        }
    }

    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 24 * 60 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("BackgroundBackup: submit failed: \(error.localizedDescription)")
        }
    }

    private static func handle(_ task: BGProcessingTask) {
        schedule()

        guard BackupBridgeKt.backupAvailable() else {
            task.setTaskCompleted(success: true)
            return
        }

        let completion = OneShotCompletion(task)
        let run = BackupBridgeKt.startScheduledBackup(listener: Listener { success in
            completion.finish(success)
        })
        task.expirationHandler = {
            run.cancel()
            completion.finish(false)
        }
    }

    static func runOnAppOpen() {
        guard BackupBridgeKt.backupAvailable() else { return }
        whenOnline {
            _ = BackupBridgeKt.startBackupOnAppOpen(listener: Listener { _ in })
        }
    }

    private static let networkQueue = DispatchQueue(label: "app.servicecalendar.backup.network")

    /// One-shot network check: the first NWPathMonitor state arrives right after start. A permanent monitor is unsuitable because its initial currentPath reports no network.
    private static func whenOnline(_ action: @escaping @Sendable @MainActor () -> Void) {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            monitor.pathUpdateHandler = nil
            monitor.cancel()
            if path.status == .satisfied { DispatchQueue.main.async { action() } }
        }
        monitor.start(queue: networkQueue)
    }
}

private final class Listener: NSObject, BackupRunListener {
    private let handler: (Bool) -> Void
    init(_ handler: @escaping (Bool) -> Void) { self.handler = handler }
    func onFinished(success: Bool) { handler(success) }
}

/// Guarantees setTaskCompleted is called exactly once; completion and expiration can race.
private final class OneShotCompletion {
    private let task: BGTask
    private let lock = NSLock()
    private var done = false
    init(_ task: BGTask) { self.task = task }
    func finish(_ success: Bool) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { task.setTaskCompleted(success: success) }
    }
}
