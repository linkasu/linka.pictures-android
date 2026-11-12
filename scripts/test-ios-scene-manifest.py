#!/usr/bin/env python3
"""Regression for the iOS 27 launch trap; validates source and compiled plists."""
import copy
import importlib.util
from pathlib import Path
import plistlib
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prerelease', ROOT / 'scripts/check-prerelease.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class SceneManifestTests(unittest.TestCase):
    def manifest(self):
        return {
            'UIApplicationSceneManifest': {
                'UIApplicationSupportsMultipleScenes': False,
                'UISceneConfigurations': {
                    'UIWindowSceneSessionRoleApplication': [{
                        'UISceneConfigurationName': 'Default Configuration',
                        'UISceneDelegateClassName': 'LinkaPokazhi.SceneDelegate',
                    }],
                },
            },
        }

    def test_scene_configuration_is_required(self):
        for info in ({}, {'UIApplicationSceneManifest': {}},
                     {'UIApplicationSceneManifest': {'UISceneConfigurations': {}}}):
            with self.subTest(info=info), self.assertRaises(ValueError):
                checks.validate_scene_manifest(info)

    def test_programmatic_single_scene(self):
        checks.validate_scene_manifest(self.manifest())

    def test_rejects_wrong_class_storyboard_and_multiwindow(self):
        for key, value in [('UISceneDelegateClassName', 'MissingDelegate'),
                           ('UISceneStoryboardFile', 'Main'),
                           ('UISceneConfigurationName', '')]:
            info = self.manifest()
            scene = info['UIApplicationSceneManifest']['UISceneConfigurations']['UIWindowSceneSessionRoleApplication'][0]
            scene[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                checks.validate_scene_manifest(info)
        info = copy.deepcopy(self.manifest())
        info['UIApplicationSceneManifest']['UIApplicationSupportsMultipleScenes'] = True
        with self.assertRaises(ValueError):
            checks.validate_scene_manifest(info)

    def test_source_manifest(self):
        checks.validate_scene_manifest(plistlib.loads((ROOT / 'iosApp/Info.plist').read_bytes()))


if __name__ == '__main__':
    unittest.main()
