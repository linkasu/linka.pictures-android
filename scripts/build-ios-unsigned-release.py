#!/usr/bin/env python3
"""Local unsigned archive only. No signing, account access, export or upload.

Requires confirmed IOS_BUNDLE_ID, RELEASE_VERSION, RELEASE_BUILD_NUMBER in env.
Approved invocation:
IOS_BUNDLE_ID=su.linka.pictures RELEASE_VERSION=2.0.0 RELEASE_BUILD_NUMBER=1 python3 -B scripts/build-ios-unsigned-release.py
Run sequentially under the build owner's scheduling; not alongside Gradle/Xcode.
"""
import os
from pathlib import Path
import subprocess
import sys

from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[1]


def main():
    spec = spec_from_file_location('prerelease', ROOT / 'scripts/check-prerelease.py')
    checks = module_from_spec(spec)
    spec.loader.exec_module(checks)
    checks.validate_ios(os.environ.get('IOS_BUNDLE_ID', ''),
                        os.environ.get('RELEASE_VERSION', ''),
                        os.environ.get('RELEASE_BUILD_NUMBER', ''))
    env = dict(os.environ, CONFIGURATION='Release', PLATFORM_NAME='iphoneos', ARCHS='arm64',
               BUILT_PRODUCTS_DIR=str(ROOT / 'shared/build/compose/ios/iosArm64'),
               UNLOCALIZED_RESOURCES_FOLDER_PATH='.')
    subprocess.run(['./gradlew', ':shared:linkReleaseFrameworkIosArm64',
                    ':shared:syncComposeResourcesForIos'], cwd=ROOT, env=env, check=True)
    archive = ROOT / 'build/prerelease/LinkaPokazhi-unsigned.xcarchive'
    subprocess.run(['xcodebuild', '-project', 'iosApp/LinkaPokazhi.xcodeproj',
                    '-scheme', 'LinkaPokazhi', '-configuration', 'Release',
                    '-destination', 'generic/platform=iOS', '-archivePath', str(archive),
                    'CODE_SIGNING_ALLOWED=NO', 'CODE_SIGNING_REQUIRED=NO',
                    f'IOS_BUNDLE_ID={os.environ["IOS_BUNDLE_ID"]}',
                    f'RELEASE_VERSION={os.environ["RELEASE_VERSION"]}',
                    f'RELEASE_BUILD_NUMBER={os.environ["RELEASE_BUILD_NUMBER"]}',
                    'archive'], cwd=ROOT, check=True)
    checks.check_app(archive / 'Products/Applications/LinkaPokazhi.app')
    print(f'Unsigned archive: {archive}; not installable/upload-ready')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        sys.exit(str(error))
