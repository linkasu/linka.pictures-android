import SwiftUI

@main
struct LinkaPicturesApp: App {
  var body: some Scene {
    WindowGroup {
      if #available(iOS 16.0, *) {
        MainView()
      } else {
        Text("iOS 16.0 or newer required")
      }
    }
  }
}

