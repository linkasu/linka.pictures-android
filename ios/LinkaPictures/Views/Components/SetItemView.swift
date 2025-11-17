import SwiftUI
import shared

@available(iOS 15.0, *)
struct SetItemView: View {
  let manifest: SetManifest
  let imagePath: String?
  
  var displayName: String {
    manifest.name.replacingOccurrences(of: ".linka", with: "")
  }
  
  var body: some View {
    ZStack {
      if let imagePath = imagePath {
        AsyncImage(url: URL(fileURLWithPath: imagePath)) { phase in
          switch phase {
          case .empty:
            Color.gray.opacity(0.3)
          case .success(let image):
            image
              .resizable()
              .aspectRatio(contentMode: .fill)
          case .failure:
            Color.gray.opacity(0.3)
              .overlay(
                Image(systemName: "photo")
                  .foregroundColor(.white)
              )
          @unknown default:
            Color.gray.opacity(0.3)
          }
        }
      } else {
        Color.gray.opacity(0.3)
          .overlay(
            Image(systemName: "photo")
              .foregroundColor(.white)
          )
      }
      
      Text(displayName)
        .font(.system(size: 14))
        .foregroundColor(.white)
        .padding(4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.6))
    }
    .frame(height: 128)
    .clipShape(RoundedRectangle(cornerRadius: 4))
  }
}

