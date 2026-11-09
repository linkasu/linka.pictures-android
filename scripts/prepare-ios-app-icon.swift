#!/usr/bin/env swift
// Reuse the existing LINKa logo, flattening alpha for Apple's opaque 1024px icon.
import AppKit
import Foundation

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let source = root.appendingPathComponent("app/src/debug/ic_launcher-playstore.png")
guard let image = NSImage(contentsOf: source),
      let context = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8,
          bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { fatalError("Cannot read existing app logo") }
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
let bounds = NSRect(x: 0, y: 0, width: 1024, height: 1024)
NSColor.white.setFill()
bounds.fill()
image.draw(in: bounds)
NSGraphicsContext.restoreGraphicsState()
guard let rendered = context.makeImage(),
      let png = NSBitmapImageRep(cgImage: rendered).representation(using: .png, properties: [:]) else { fatalError("PNG encoding failed") }
let output = root.appendingPathComponent("iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try png.write(to: output)
assert(NSBitmapImageRep(data: png)?.hasAlpha == false)
print("Prepared opaque 1024px AppIcon from \(source.path)")
