import KotlinModules
import SwiftUI
import BackgroundTasks

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> some UIViewController {
        ViewControllerKt.ViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewControllerType, context: Context) {}
}

struct ContentView: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ComposeView().ignoresSafeArea()
            .onAppear { applyStatusBarStyle(colorScheme) }
            .onChange(of: colorScheme) { _, scheme in applyStatusBarStyle(scheme) }
    }

    /// Works only because Info.plist sets UIViewControllerBasedStatusBarAppearance = NO; the SwiftUI host does not ask the nested Compose controller for its style.
    private func applyStatusBarStyle(_ scheme: ColorScheme) {
        UIApplication.shared.setStatusBarStyle(scheme == .dark ? .darkContent : .lightContent, animated: false)
    }
}

@main
struct iosApp: App {
    init() {
        ViewControllerKt.setUpAppContainer(googleAuth: GoogleDriveAuth())
        BackgroundBackup.register()
        BackgroundBackup.scheduleIfNeeded()
    }

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { BackgroundBackup.runOnAppOpen() }
        }
    }
}
