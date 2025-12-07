import SwiftUI

struct GridSettingsView: View {
  @Binding var isOutput: Bool
  @Binding var isPagesButtons: Bool
  @Environment(\.dismiss) var dismiss
  
  let onSave: () -> Void
  
  var body: some View {
    NavigationView {
      Form {
        Section {
          Toggle(isOn: $isOutput) {
            VStack(alignment: .leading, spacing: 4) {
              Text(NSLocalizedString("show_output_line", comment: ""))
                .font(.body)
              Text(NSLocalizedString("show_output_line_description", comment: ""))
                .font(.caption)
                .foregroundColor(.gray)
            }
          }
          
          Toggle(isOn: $isPagesButtons) {
            VStack(alignment: .leading, spacing: 4) {
              Text(NSLocalizedString("show_page_buttons", comment: ""))
                .font(.body)
              Text(NSLocalizedString("show_page_buttons_description", comment: ""))
                .font(.caption)
                .foregroundColor(.gray)
            }
          }
        }
      }
      .navigationTitle(NSLocalizedString("grid_activity_settings", comment: ""))
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .navigationBarLeading) {
          Button(NSLocalizedString("cancel", comment: "")) {
            dismiss()
          }
        }
        
        ToolbarItem(placement: .navigationBarTrailing) {
          Button(NSLocalizedString("ok", comment: "")) {
            onSave()
            dismiss()
          }
        }
      }
    }
  }
}

