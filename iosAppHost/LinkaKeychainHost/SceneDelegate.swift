import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let label = UILabel()
        label.text = "LINKa isolated Keychain tests"
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.backgroundColor = .systemBackground
        let controller = UIViewController()
        controller.view = label
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }
}
