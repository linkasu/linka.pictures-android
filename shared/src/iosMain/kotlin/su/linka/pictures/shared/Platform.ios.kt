package su.linka.pictures.shared

import platform.UIKit.UIDevice

actual fun platformName(): String = 
  UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

