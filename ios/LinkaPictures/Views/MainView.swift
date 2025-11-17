import SwiftUI
import shared

@available(iOS 16.0, *)
struct MainView: View {
  @StateObject private var viewModel = MainViewModel()
  
  @State private var showDeleteAlert = false
  @State private var showRenameAlert = false
  @State private var selectedManifest: SetManifest?
  @State private var newName = ""
  
  private let columns = [
    GridItem(.flexible(), spacing: 8),
    GridItem(.flexible(), spacing: 8),
    GridItem(.flexible(), spacing: 8)
  ]
  
  var body: some View {
    NavigationStack {
      ZStack {
        if viewModel.isLoading {
          ProgressView()
        } else if viewModel.sets.isEmpty {
          Text("No sets available")
            .foregroundColor(.gray)
        } else {
          ScrollView {
            LazyVGrid(columns: columns, spacing: 8) {
              ForEach(viewModel.sets, id: \.name) { manifest in
                SetItemView(
                  manifest: manifest,
                  imagePath: viewModel.getImagePath(for: manifest)
                )
                .onTapGesture {
                  openSet(manifest)
                }
                .contextMenu {
                  Button {
                    openSet(manifest)
                  } label: {
                    Label(NSLocalizedString("open", comment: ""), systemImage: "square.and.arrow.up")
                  }
                  
                  Button {
                    editSet(manifest)
                  } label: {
                    Label(NSLocalizedString("edit", comment: ""), systemImage: "pencil")
                  }
                  
                  Button {
                    selectedManifest = manifest
                    newName = manifest.name.replacingOccurrences(of: ".linka", with: "")
                    showRenameAlert = true
                  } label: {
                    Label(NSLocalizedString("rename", comment: ""), systemImage: "character.cursor.ibeam")
                  }
                  
                  Divider()
                  
                  Button(role: .destructive) {
                    selectedManifest = manifest
                    showDeleteAlert = true
                  } label: {
                    Label(NSLocalizedString("delete", comment: ""), systemImage: "trash")
                  }
                }
              }
            }
            .padding(8)
          }
        }
      }
      .navigationTitle(NSLocalizedString("your_sets", comment: ""))
      .navigationBarTitleDisplayMode(.large)
      .toolbar {
        ToolbarItem(placement: .navigationBarTrailing) {
          Button {
            // TODO: Navigate to settings
          } label: {
            Image(systemName: "gear")
          }
        }
        
        ToolbarItem(placement: .navigationBarTrailing) {
          Button {
            createSet()
          } label: {
            Image(systemName: "plus")
          }
        }
      }
      .alert(NSLocalizedString("confirm_delete", comment: ""), isPresented: $showDeleteAlert) {
        Button(NSLocalizedString("cancel", comment: ""), role: .cancel) {
          selectedManifest = nil
        }
        Button(NSLocalizedString("delete", comment: ""), role: .destructive) {
          if let manifest = selectedManifest {
            viewModel.deleteSet(manifest)
          }
          selectedManifest = nil
        }
      } message: {
        Text(NSLocalizedString("delete_confirmation_message", comment: ""))
      }
      .alert(NSLocalizedString("rename_set", comment: ""), isPresented: $showRenameAlert) {
        TextField(NSLocalizedString("enter_new_name", comment: ""), text: $newName)
        Button(NSLocalizedString("cancel", comment: ""), role: .cancel) {
          selectedManifest = nil
          newName = ""
        }
        Button(NSLocalizedString("ok", comment: "")) {
          if let manifest = selectedManifest, !newName.isEmpty {
            viewModel.renameSet(manifest, newName: newName)
          }
          selectedManifest = nil
          newName = ""
        }
      }
      .alert(NSLocalizedString("set_open_error", comment: ""), isPresented: $viewModel.showError) {
        Button(NSLocalizedString("ok", comment: ""), role: .cancel) {
          viewModel.showError = false
        }
      } message: {
        if let errorMessage = viewModel.errorMessage {
          Text(errorMessage)
        }
      }
      .refreshable {
        viewModel.loadSets()
      }
    }
    .accentColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
  }
  
  private func openSet(_ manifest: SetManifest) {
    // TODO: Navigate to GridView
    print("Open set: \(manifest.name)")
  }
  
  private func editSet(_ manifest: SetManifest) {
    // TODO: Navigate to SetEditView
    print("Edit set: \(manifest.name)")
  }
  
  private func createSet() {
    // TODO: Navigate to SetEditView for new set
    print("Create set")
  }
}

@available(iOS 16.0, *)
#Preview {
  MainView()
}

