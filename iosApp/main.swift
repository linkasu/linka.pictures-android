import Shared
import UIKit
import CryptoKit

final class BootstrapAppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        IdentitySignatureVerifier.shared.initialize { key, message, signature in
            guard key.size == 32, signature.size == 64 else { return KotlinBoolean(bool: false) }
            func data(_ bytes: KotlinByteArray) -> Data {
                Data((0..<Int(bytes.size)).map { UInt8(bitPattern: bytes.get(index: Int32($0))) })
            }
            guard let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: data(key)) else { return KotlinBoolean(bool: false) }
            return KotlinBoolean(bool: publicKey.isValidSignature(data(signature), for: data(message)))
        }
        let controller = MainViewControllerKt.MainViewController()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(BootstrapAppDelegate.self))
