import SwiftUI
import shared

struct CardGridView: View {
  let manifest: SetManifest
  let workspaceId: String
  let fileManager: LinkaFileManager
  let showPagination: Bool
  let onCardSelect: (Card) -> Void
  
  @State private var currentPage: Int = 0
  
  var body: some View {
    GeometryReader { geometry in
      VStack(spacing: 0) {
        let spacing: CGFloat = 8
        let paginationHeight: CGFloat = (showPagination && pagesCount > 1) ? 52 : 0
        let padding: CGFloat = 16
        let totalHorizontalSpacing = spacing * (CGFloat(manifest.columns) - 1)
        let totalVerticalSpacing = spacing * (CGFloat(manifest.rows) - 1)
        
        let availableWidth = geometry.size.width - padding - totalHorizontalSpacing
        let availableHeight = geometry.size.height - padding - paginationHeight - totalVerticalSpacing
        
        let cellWidth = availableWidth / CGFloat(manifest.columns)
        let cellHeight = availableHeight / CGFloat(manifest.rows)
        let cellSize = min(cellWidth, cellHeight)
        
        let columns = Array(repeating: GridItem(.fixed(cellSize), spacing: spacing), count: Int(manifest.columns))
        
        LazyVGrid(columns: columns, spacing: spacing) {
          ForEach(currentPageCards.indices, id: \.self) { index in
            let card = currentPageCards[index]
            let imagePath = getImagePath(for: card)
            
            Button {
              if card.cardType != 2 {
                onCardSelect(card)
              }
            } label: {
              CardButtonView(
                card: card,
                imagePath: imagePath,
                isOutputCard: false
              )
            }
            .buttonStyle(PlainButtonStyle())
            .frame(width: cellSize, height: cellSize)
          }
        }
        .padding(8)
        
        if showPagination && pagesCount > 1 {
          Divider()
            .padding(.vertical, 4)
          
          HStack(spacing: 20) {
            Button {
              prevPage()
            } label: {
              Image(systemName: "chevron.left")
                .font(.title)
                .foregroundColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
            }
            .disabled(currentPage == 0)
            
            Spacer()
            
            Text("\(currentPage + 1) / \(pagesCount)")
              .font(.body)
              .foregroundColor(.gray)
            
            Spacer()
            
            Button {
              nextPage()
            } label: {
              Image(systemName: "chevron.right")
                .font(.title)
                .foregroundColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
            }
            .disabled(currentPage >= pagesCount - 1)
          }
          .padding(.horizontal)
          .frame(height: 44)
        }
      }
    }
  }
  
  private var pageSize: Int {
    Int(manifest.rows * manifest.columns)
  }
  
  private var pagesCount: Int {
    let total = manifest.cards.count
    guard pageSize > 0 else { return 0 }
    return max(1, Int(ceil(Double(total) / Double(pageSize))))
  }
  
  private var currentPageCards: [Card] {
    let startIndex = currentPage * pageSize
    let cardsArray = Array(manifest.cards)
    let endIndex = min(startIndex + pageSize, cardsArray.count)
    
    guard startIndex < cardsArray.count else {
      return []
    }
    
    var pageCards: [Card] = []
    for i in startIndex..<endIndex {
      if let card = cardsArray[i] as? Card {
        pageCards.append(card)
      }
    }
    
    while pageCards.count < pageSize {
      pageCards.append(Card(id: 0, cardType: 2))
    }
    
    return pageCards
  }
  
  private func nextPage() {
    if currentPage < pagesCount - 1 {
      currentPage += 1
    }
  }
  
  private func prevPage() {
    if currentPage > 0 {
      currentPage -= 1
    }
  }
  
  private func getImagePath(for card: Card) -> String? {
    guard let imagePath = card.imagePath else { return nil }
    return fileManager.getAudioPath(workspaceId: workspaceId, audioPath: imagePath)
  }
}

