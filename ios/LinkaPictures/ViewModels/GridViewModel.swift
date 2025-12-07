import SwiftUI
import shared
import AVFoundation

class GridViewModel: ObservableObject {
  @Published var linkaSet: LinkaSet?
  @Published var manifest: SetManifest?
  @Published var selectedCards: [Card] = []
  @Published var isLoading = false
  @Published var errorMessage: String?
  @Published var showError = false
  @Published var showSettings = false
  @Published var isPlaying = false
  
  @Published var isOutput: Bool = true
  @Published var isPagesButtons: Bool = true
  
  private let fileManager = LinkaFileManager()
  private var audioPlayer: AVAudioPlayer?
  private let speechSynthesizer = AVSpeechSynthesizer()
  private var currentPlayIndex = 0
  private var shouldContinuePlaying = false
  
  var fileName: String = ""
  var workspaceId: String = ""
  
  init() {}
  
  func loadSet(fileName: String) {
    self.fileName = fileName
    isLoading = true
    errorMessage = nil
    
    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
      guard let self = self else { return }
      
      do {
        let loadedSet = self.fileManager.getSet(fileName: fileName)
        
        DispatchQueue.main.async {
          self.linkaSet = loadedSet
          self.manifest = loadedSet.manifest
          self.workspaceId = loadedSet.workspaceId
          self.loadSettings()
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
  
  func onCardSelect(_ card: Card) {
    guard let manifest = manifest else { return }
    
    if !isOutput {
      playCard(card, completion: nil)
    } else {
      selectedCards.append(card)
    }
  }
  
  func backspace() {
    if !selectedCards.isEmpty {
      selectedCards.removeLast()
    }
  }
  
  func clear() {
    selectedCards.removeAll()
  }
  
  func speak() {
    guard let manifest = manifest else { return }
    
    if manifest.withoutSpace {
      speakWithTTS()
    } else {
      if isPlaying {
        stopPlaying()
      } else {
        playCards()
      }
    }
  }
  
  func stopPlaying() {
    shouldContinuePlaying = false
    isPlaying = false
    audioPlayer?.stop()
    audioPlayer = nil
    speechSynthesizer.stopSpeaking(at: .immediate)
  }
  
  func saveSettings() {
    let settings = GridSettings(isOutput: isOutput, isPagesButtons: isPagesButtons)
    let key = "grid_settings_\(fileName)"
    UserDefaults.standard.set(settings.toInt(), forKey: key)
  }
  
  func loadSettings() {
    let key = "grid_settings_\(fileName)"
    let defaultValue = 3
    let value = UserDefaults.standard.integer(forKey: key)
    let actualValue = value == 0 ? defaultValue : value
    let settings = GridSettings.fromInt(value: Int32(actualValue))
    isOutput = settings.isOutput
    isPagesButtons = settings.isPagesButtons
  }
  
  func getImagePath(for card: Card) -> String? {
    guard let imagePath = card.imagePath else { return nil }
    return fileManager.getAudioPath(workspaceId: workspaceId, audioPath: imagePath)
  }
  
  private func speakWithTTS() {
    var text = ""
    for card in selectedCards {
      if card.cardType == 0 {
        text += card.title ?? ""
      } else if card.cardType == 1 {
        text += " "
      }
    }
    
    if !text.isEmpty {
      let utterance = AVSpeechUtterance(string: text)
      utterance.voice = AVSpeechSynthesisVoice(language: "ru-RU")
      speechSynthesizer.speak(utterance)
    }
  }
  
  private func playCards() {
    guard !selectedCards.isEmpty else { return }
    
    shouldContinuePlaying = true
    isPlaying = true
    currentPlayIndex = 0
    playNextCard()
  }
  
  private func playNextCard() {
    guard shouldContinuePlaying else {
      isPlaying = false
      return
    }
    
    guard currentPlayIndex < selectedCards.count else {
      isPlaying = false
      return
    }
    
    let card = selectedCards[currentPlayIndex]
    currentPlayIndex += 1
    
    playCard(card) { [weak self] in
      guard let self = self else { return }
      if self.shouldContinuePlaying {
        self.playNextCard()
      } else {
        self.isPlaying = false
      }
    }
  }
  
  private func playCard(_ card: Card, completion: (() -> Void)?) {
    guard let audioPath = card.audioPath else {
      completion?()
      return
    }
    
    guard let fullPath = fileManager.getAudioPath(workspaceId: workspaceId, audioPath: audioPath) else {
      completion?()
      return
    }
    
    let url = URL(fileURLWithPath: fullPath)
    
    guard FileManager.default.fileExists(atPath: fullPath) else {
      completion?()
      return
    }
    
    do {
      audioPlayer = try AVAudioPlayer(contentsOf: url)
      
      if let player = audioPlayer {
        player.prepareToPlay()
        
        if let completion = completion {
          NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: player,
            queue: .main
          ) { _ in
            completion()
          }
          
          DispatchQueue.main.asyncAfter(deadline: .now() + player.duration + 0.1) {
            completion()
          }
        }
        
        player.play()
      } else {
        completion?()
      }
    } catch {
      print("Error playing audio: \(error)")
      completion?()
    }
  }
}

struct GridSettings {
  var isOutput: Bool
  var isPagesButtons: Bool
  
  func toInt() -> Int {
    var value = 0
    if isOutput { value += 1 }
    if isPagesButtons { value += 2 }
    return value
  }
  
  static func fromInt(value: Int32) -> GridSettings {
    let isOutput = (value == 1 || value == 3)
    let isPagesButtons = (value == 2 || value == 3)
    return GridSettings(isOutput: isOutput, isPagesButtons: isPagesButtons)
  }
}

