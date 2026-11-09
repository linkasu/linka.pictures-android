import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let label = UILabel()
        label.text = "LINKa isolated Keychain tests"
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.backgroundColor = .systemBackground
        let controller = UIViewController()
        controller.view = label
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
