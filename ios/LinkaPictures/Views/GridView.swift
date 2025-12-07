import SwiftUI
import shared

@available(iOS 16.0, *)
struct GridView: View {
  let manifest: SetManifest
  
  @StateObject private var viewModel = GridViewModel()
  @State private var showSettingsSheet = false
  @Environment(\.dismiss) var dismiss
  
  var body: some View {
    ZStack {
      if viewModel.isLoading {
        ProgressView()
      } else if let loadedManifest = viewModel.manifest {
        GeometryReader { geometry in
          VStack(spacing: 0) {
            if viewModel.isOutput {
              OutputLineView(
                cards: viewModel.selectedCards,
                withoutSpace: loadedManifest.withoutSpace,
                workspaceId: viewModel.workspaceId,
                fileManager: LinkaFileManager(),
                onBackspace: {
                  viewModel.backspace()
                },
                onClear: {
                  viewModel.clear()
                },
                onSpeak: {
                  viewModel.speak()
                }
              )
              .frame(height: 80)
            }
            
            CardGridView(
              manifest: loadedManifest,
              workspaceId: viewModel.workspaceId,
              fileManager: LinkaFileManager(),
              showPagination: viewModel.isPagesButtons,
              onCardSelect: { card in
                viewModel.onCardSelect(card)
              }
            )
            .frame(height: viewModel.isOutput ? geometry.size.height - 80 : geometry.size.height)
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
          }
        }
      } else {
        Text(NSLocalizedString("set_open_error", comment: ""))
          .foregroundColor(.gray)
      }
    }
    .navigationTitle(manifest.name.replacingOccurrences(of: ".linka", with: ""))
    .navigationBarTitleDisplayMode(.inline)
    .navigationBarBackButtonHidden(false)
    .toolbar {
      ToolbarItem(placement: .navigationBarTrailing) {
        Button {
          showSettingsSheet = true
        } label: {
          Image(systemName: "gear")
        }
      }
    }
    .sheet(isPresented: $showSettingsSheet) {
      GridSettingsView(
        isOutput: $viewModel.isOutput,
        isPagesButtons: $viewModel.isPagesButtons,
        onSave: {
          viewModel.saveSettings()
        }
      )
    }
    .alert(NSLocalizedString("set_open_error", comment: ""), isPresented: $viewModel.showError) {
      Button(NSLocalizedString("ok", comment: ""), role: .cancel) {
        viewModel.showError = false
        dismiss()
      }
    } message: {
      if let errorMessage = viewModel.errorMessage {
        Text(errorMessage)
      }
    }
    .onAppear {
      viewModel.loadSet(fileName: manifest.name)
    }
    .onDisappear {
      viewModel.stopPlaying()
    }
    .accentColor(Color(red: 0x19 / 255.0, green: 0x73 / 255.0, blue: 0x77 / 255.0))
  }
}

