import SwiftUI
import shared

class MainViewModel: ObservableObject {
  @Published var sets: [SetManifest] = []
  @Published var isLoading = false
  @Published var errorMessage: String?
  @Published var showError = false
  
  private let fileManager = LinkaFileManager()
  
  init() {
    loadSets()
  }
  
  func loadSets() {
    isLoading = true
    errorMessage = nil
    
    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
      guard let self = self else { return }
      
      do {
        self.fileManager.loadDefaultSets()
        let loadedSets = self.fileManager.getSets()
        
        DispatchQueue.main.async {
          self.sets = loadedSets
          self.isLoading = false
        }
      } catch {
        DispatchQueue.main.async {
          self.errorMessage = error.localizedDescription
          self.showError = true
          self.isLoading = false
        }
      }
    }
  }
  
  func deleteSet(_ manifest: SetManifest) {
    let result = fileManager.deleteSet(manifest: manifest)
    
    if let error = (result as? NSObject)?.value(forKey: "exceptionOrNull") as? Error {
      errorMessage = error.localizedDescription
      showError = true
    } else {
      sets.removeAll { $0.name == manifest.name }
    }
  }
  
  func renameSet(_ manifest: SetManifest, newName: String) {
    let result = fileManager.renameSet(manifest: manifest, newName: newName)
    
    if let error = (result as? NSObject)?.value(forKey: "exceptionOrNull") as? Error {
      errorMessage = error.localizedDescription
      showError = true
    } else {
      loadSets()
    }
  }
  
  func getImagePath(for manifest: SetManifest) -> String? {
    guard let defaultBitmap = manifest.getDefaultBitmap() else {
      return nil
    }
    
    return fileManager.getImagePath(manifest: manifest, imagePath: defaultBitmap)
  }
}

