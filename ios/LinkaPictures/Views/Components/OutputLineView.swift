import SwiftUI
import shared

struct OutputLineView: View {
  let cards: [Card]
  let withoutSpace: Bool
  let workspaceId: String
  let fileManager: LinkaFileManager
  let onBackspace: () -> Void
  let onClear: () -> Void
  let onSpeak: () -> Void
  
  var body: some View {
    HStack(spacing: 0) {
      Button {
        onBackspace()
      } label: {
        Image(systemName: "delete.left.fill")
          .font(.title2)
          .foregroundColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
      .frame(width: 50)
      
      if withoutSpace {
        Text(outputText)
          .font(.system(size: 18))
          .lineLimit(nil)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .padding(.horizontal, 8)
          .background(Color(UIColor.secondarySystemBackground))
          .cornerRadius(8)
          .padding(.horizontal, 4)
      } else {
        OutputGridView(
          cards: cards,
          workspaceId: workspaceId,
          fileManager: fileManager
        )
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 4)
      }
      
      Button {
        onSpeak()
      } label: {
        Image(systemName: "speaker.wave.2.fill")
          .font(.title2)
          .foregroundColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
      .frame(width: 50)
      
      Button {
        onClear()
      } label: {
        Image(systemName: "xmark.circle.fill")
          .font(.title2)
          .foregroundColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
      .frame(width: 50)
    }
    .frame(height: 80)
    .background(Color(UIColor.systemBackground))
    .overlay(
      Rectangle()
        .fill(Color(UIColor.separator))
        .frame(height: 1),
      alignment: .bottom
    )
  }
  
  private var outputText: String {
    var builder = ""
    for card in cards {
      if card.cardType == 0 {
        builder += card.title ?? ""
      } else if card.cardType == 1 {
        builder += " "
      }
    }
    return builder
  }
}

