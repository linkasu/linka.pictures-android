import SwiftUI
import shared

struct CardButtonView: View {
  let card: Card?
  let imagePath: String?
  let isOutputCard: Bool
  
  var body: some View {
    if let card = card {
      if card.cardType == 2 {
        Color.clear
          .frame(width: 0, height: 0)
          .hidden()
      } else {
        cardContent(card)
      }
    } else {
      Color.clear
    }
  }
  
  @ViewBuilder
  private func cardContent(_ card: Card) -> some View {
    GeometryReader { geometry in
      VStack(spacing: 0) {
        if card.cardType == 0 {
          cardTypeZero(card, size: geometry.size)
        } else if card.cardType == 1 {
          cardTypeOne(size: geometry.size)
        } else if card.cardType == 3 {
          cardTypeThree(size: geometry.size)
        }
      }
      .background(Color(UIColor.systemBackground))
      .cornerRadius(isOutputCard ? 8 : 12)
      .shadow(color: Color.black.opacity(0.15), radius: 3, x: 0, y: 2)
      .overlay(
        RoundedRectangle(cornerRadius: isOutputCard ? 8 : 12)
          .stroke(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0).opacity(0.3), lineWidth: isOutputCard ? 1.5 : 2)
      )
    }
    .aspectRatio(1, contentMode: .fit)
  }
  
  @ViewBuilder
  private func cardTypeZero(_ card: Card, size: CGSize) -> some View {
    VStack(spacing: 0) {
      if let imagePath = imagePath {
        AsyncImage(url: URL(fileURLWithPath: imagePath)) { phase in
          switch phase {
          case .empty:
            Color.gray.opacity(0.2)
          case .success(let image):
            image
              .resizable()
              .scaledToFill()
          case .failure:
            Color.gray.opacity(0.2)
          @unknown default:
            Color.gray.opacity(0.2)
          }
        }
        .frame(width: size.width, height: size.height * 0.7)
        .clipped()
      } else {
        Color.gray.opacity(0.2)
          .frame(width: size.width, height: size.height * 0.7)
      }
      
      if let title = card.title {
        Text(title)
          .font(.system(size: 13, weight: .medium))
          .minimumScaleFactor(0.5)
          .lineLimit(2)
          .multilineTextAlignment(.center)
          .frame(height: size.height * 0.3)
          .frame(maxWidth: .infinity)
          .padding(.horizontal, 4)
          .padding(.vertical, 4)
          .background(Color(UIColor.systemBackground))
      }
    }
  }
  
  @ViewBuilder
  private func cardTypeOne(size: CGSize) -> some View {
    VStack {
      Image(systemName: "space")
        .resizable()
        .aspectRatio(contentMode: .fit)
        .frame(width: size.width * 0.5, height: size.height * 0.5)
        .foregroundColor(.gray)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
  }
  
  @ViewBuilder
  private func cardTypeThree(size: CGSize) -> some View {
    VStack {
      Image(systemName: "plus")
        .resizable()
        .aspectRatio(contentMode: .fit)
        .frame(width: size.width * 0.4, height: size.height * 0.4)
        .foregroundColor(.blue)
      
      Text(NSLocalizedString("create_card", comment: ""))
        .font(.system(size: 12))
        .foregroundColor(.blue)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
  }
}

