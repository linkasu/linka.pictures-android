#!/usr/bin/env python3
"""Offline release wiring/selftests; optionally inspect an unsigned .app.

Apple reasons verified against the official NSPrivacyAccessedAPIType reference:
https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype
35F9.1: recording timer; C617.1: container files; 3B52.1: picker-granted files.
Only required-reason APIs are declared: privacy labels need a separate review.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def validate_ios(bundle, version, build):
    if not re.fullmatch(r'[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+', bundle) or bundle.endswith('.development'):
        raise ValueError('Release needs confirmed IOS_BUNDLE_ID (not the development ID)')
    if not re.fullmatch(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?', version):
        raise ValueError('Release needs RELEASE_VERSION: major.minor[.patch]')
    if not re.fullmatch(r'[1-9][0-9]{0,3}(?:\.[0-9]{1,2}){0,2}', build):
        raise ValueError('Release needs RELEASE_BUILD_NUMBER: positive 1-4 digits, optional .xx.xx')


def load_plist(path):
    return plistlib.loads(path.read_bytes())


def validate_aab_signature_result(returncode, output):
    # jarsigner returns zero for unsigned archives too; self-signed chain warnings are allowed.
    if returncode != 0 or not re.search(r'^jar verified\.$', output, re.M) or 'unsigned' in output.lower():
        raise ValueError('AAB signature verification failed: require a verified archive with no unsigned entries')


def check_aab(path):
    result = subprocess.run(['jarsigner', '-J-Duser.language=en', '-J-Duser.country=US',
                             '-verify', str(path)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, check=False)
    validate_aab_signature_result(result.returncode, result.stdout)
    print(f'PASS: AAB cryptographic signature {path} (not signer identity or upload readiness)')


def check_app(app):
    info = load_plist(app / 'Info.plist')
    validate_ios(info['CFBundleIdentifier'], info['CFBundleShortVersionString'], info['CFBundleVersion'])
    assert set(info['UISupportedInterfaceOrientations~ipad']) == {
        'UIInterfaceOrientationPortrait', 'UIInterfaceOrientationPortraitUpsideDown',
        'UIInterfaceOrientationLandscapeLeft', 'UIInterfaceOrientationLandscapeRight'}, 'iPad multitasking orientations missing'
    assert (app / info['CFBundleExecutable']).is_file(), 'Missing executable'
    assert (app / 'Assets.car').is_file(), 'Missing compiled AppIcon assets'
    assert info.get('CFBundleIcons', {}).get('CFBundlePrimaryIcon'), 'Missing primary app icon metadata'
    assert load_plist(app / 'PrivacyInfo.xcprivacy') == load_plist(ROOT / 'iosApp/PrivacyInfo.xcprivacy')
    resources = app / 'compose-resources'
    assert any(resources.rglob('*.*')), 'Missing Compose resources'
    for locale in ('en', 'ru'):
        assert (app / f'{locale}.lproj/InfoPlist.strings').is_file(), f'Missing {locale} permissions'
    print(f'PASS: artifact structure {app} (not signing or upload readiness)')


def check():
    project = json.loads(subprocess.check_output(['plutil', '-convert', 'json', '-o', '-',
        str(ROOT / 'iosApp/LinkaPokazhi.xcodeproj/project.pbxproj')]))['objects'] if sys.platform == 'darwin' else None
    if project:
        target = project['B00000000000000000000005']
        configs = project[target['buildConfigurationList']]['buildConfigurations']
        settings = {project[key]['name']: project[key]['buildSettings'] for key in configs}
        assert settings['Debug']['PRODUCT_BUNDLE_IDENTIFIER'] == 'su.linka.pictures.development'
        assert 'debugFramework' in str(settings['Debug']['FRAMEWORK_SEARCH_PATHS'])
        assert 'releaseFramework' in str(settings['Release']['FRAMEWORK_SEARCH_PATHS'])
        assert settings['Release']['PRODUCT_BUNDLE_IDENTIFIER'] == '$(IOS_BUNDLE_ID)'
        assert settings['Release']['MARKETING_VERSION'] == '$(RELEASE_VERSION)'
        assert settings['Release']['CURRENT_PROJECT_VERSION'] == '$(RELEASE_BUILD_NUMBER)'
        assert settings['Release']['IOS_BUNDLE_ID'] == 'su.linka.pictures'
        assert settings['Release']['RELEASE_VERSION'] == '2.0.0'
        assert settings['Release']['RELEASE_BUILD_NUMBER'] == '1'
        resources = next(project[key] for key in target['buildPhases'] if project[key]['isa'] == 'PBXResourcesBuildPhase')
        paths = {project[project[key]['fileRef']].get('path', project[project[key]['fileRef']].get('name')) for key in resources['files']}
        assert {'Assets.xcassets', 'PrivacyInfo.xcprivacy', 'InfoPlist.strings'} <= paths
        assert any('compose-resources' in path for path in paths)
    scheme = ET.parse(ROOT / 'iosApp/LinkaPokazhi.xcodeproj/xcshareddata/xcschemes/LinkaPokazhi.xcscheme')
    assert scheme.find('ArchiveAction').get('buildConfiguration') == 'Release'
    assert scheme.find('.//BuildActionEntry').get('buildForArchiving') == 'YES'
    privacy = load_plist(ROOT / 'iosApp/PrivacyInfo.xcprivacy')
    reasons = {row['NSPrivacyAccessedAPIType']: row['NSPrivacyAccessedAPITypeReasons'] for row in privacy['NSPrivacyAccessedAPITypes']}
    assert reasons == {'NSPrivacyAccessedAPICategorySystemBootTime': ['35F9.1'],
                       'NSPrivacyAccessedAPICategoryFileTimestamp': ['C617.1', '3B52.1']}
    png = (ROOT / 'iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png').read_bytes()
    assert png[:8] == b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II', png[16:24]) == (1024, 1024)
    assert png[25] == 2, 'Store AppIcon must be RGB without alpha'
    for path in ('app/build.gradle', 'shared/build.gradle'):
        gradle = (ROOT / path).read_text()
        assert 'compileSdk 36' in gradle and 'minSdk 21' in gradle
    assert 'targetSdk 36' in (ROOT / 'app/build.gradle').read_text()
    properties = (ROOT / 'gradle.properties').read_text().splitlines()
    assert 'ANDROID_VERSION_CODE=6' in properties and 'RELEASE_VERSION=2.0.0' in properties
    signing_gate = (ROOT / 'app/build.gradle').read_text().split("tasks.register('verifyReleaseSigning') {", 1)[1].split('tasks.configureEach', 1)[0]
    assert 'dependsOn verifyReleaseMetadata' in signing_gate
    assert 'if (!hasReleaseSigningConfig)' in signing_gate and 'throw new GradleException' in signing_gate
    validate_aab_signature_result(0, '\njar verified.\nWarning: signer certificate is self-signed.\n')
    for status, output in [(0, 'jar is unsigned.'), (1, 'jar verified.'),
                           (1, 'jarsigner: java.lang.SecurityException: digest error'),
                           (0, 'jar verified.\nThis jar contains unsigned entries.'),
                           (0, 'The jar will be treated as unsigned due to a disabled algorithm.'),
                           (0, '')]:
        try:
            validate_aab_signature_result(status, output)
        except ValueError:
            pass
        else:
            raise AssertionError(f'Accepted unverified AAB signature: {status}, {output}')
    validate_ios('org.example.checked', '2.0', '1')  # Synthetic selftest only, never production defaults.
    for values in [('', '2.0', '1'), ('org.example.development', '2.0', '1'),
                   ('org.example.checked', '', '1'), ('org.example.checked', '2.0', '')]:
        try:
            validate_ios(*values)
        except ValueError:
            pass
        else:
            raise AssertionError(f'Accepted missing/unsafe metadata: {values}')
    print('PASS: release wiring, icon, privacy, metadata and signature-result rejection (offline only)')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ios-settings', action='store_true')
    parser.add_argument('--app', type=Path)
    parser.add_argument('--aab', type=Path)
    args = parser.parse_args()
    try:
        if args.ios_settings:
            validate_ios(os.environ.get('PRODUCT_BUNDLE_IDENTIFIER', ''), os.environ.get('MARKETING_VERSION', ''),
                         os.environ.get('CURRENT_PROJECT_VERSION', ''))
        elif args.app:
            check_app(args.app)
        elif args.aab:
            check_aab(args.aab)
        else:
            check()
    except ValueError as error:
        sys.exit(str(error))
