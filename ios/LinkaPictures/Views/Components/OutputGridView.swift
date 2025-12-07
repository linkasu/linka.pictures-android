import SwiftUI
import shared

struct OutputGridView: View {
  let cards: [Card]
  let workspaceId: String
  let fileManager: LinkaFileManager
  
  var body: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 6) {
        ForEach(visibleCards.indices, id: \.self) { index in
          let card = visibleCards[index]
          let imagePath = getImagePath(for: card)
          
          CardButtonView(
            card: card,
            imagePath: imagePath,
            isOutputCard: true
          )
          .frame(width: 56, height: 56)
        }
      }
      .padding(.horizontal, 6)
      .padding(.vertical, 6)
    }
  }
  
  private var visibleCards: [Card] {
    cards.filter { $0.cardType == 0 }
  }
  
  private func getImagePath(for card: Card) -> String? {
    guard let imagePath = card.imagePath else { return nil }
    return fileManager.getAudioPath(workspaceId: workspaceId, audioPath: imagePath)
  }
}

