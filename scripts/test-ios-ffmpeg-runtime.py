#!/usr/bin/env python3
"""Offline contract checks; optional direct-clang runtime proof from an exact verified library root."""
import argparse
import json
import os
from pathlib import Path
import plistlib
import re
import runpy
import shutil
import subprocess
import sys
import tarfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
BUILDER = runpy.run_path(str(ROOT / 'scripts/build-ios-audio-decoder.py'))
OUT = BUILDER['OUTPUTS']


def check():
    for name, sha in BUILDER['VENDORED_PINS'].items():
        assert OUT['digest'](ROOT / 'third_party/ffmpeg' / name) == sha
    assert OUT['runtime_privacy']({'_fstat', '_lstat', '_clock_gettime'}) == {
        'NSPrivacyAccessedAPITypes': [{'NSPrivacyAccessedAPIType': 'NSPrivacyAccessedAPICategoryFileTimestamp',
                                     'NSPrivacyAccessedAPITypeReasons': ['C617.1']}]}
    assert OUT['runtime_privacy']({'_mach_absolute_time'})['NSPrivacyAccessedAPITypes'][0]['NSPrivacyAccessedAPITypeReasons'] == ['35F9.1']
    try:
        OUT['runtime_privacy']({'_statfs'})
    except RuntimeError:
        pass
    else:
        raise AssertionError('Unreviewed DiskSpace use accepted')
    definition = OUT['definition'](Path('/generated/build/iosArm64'))
    assert 'staticLibraries = liblinka_audio_decoder.a\n' in definition and 'libavcodec.a' not in definition
    assert '-framework LinkaFFmpeg' in definition and '-pthread' not in definition
    producer = (ROOT / 'scripts/build-ios-audio-decoder.py').read_text()
    assert "fixtures['vorbis'], fixtures['wav'], fixtures['mp3']" in producer
    assert "definition.write_text(OUTPUTS['definition'](prefix))" in producer
    assert "task.run(['codesign', '--force', '--sign', '-', '--timestamp=none', frameworks / OUTPUTS['RUNTIME']])" in producer
    for _, (path, size, sha) in BUILDER['PREFLIGHT']['EXPECTED'].items():
        fixture = ROOT / 'scripts/fixtures/ios-audio' / path.name
        assert not fixture.is_symlink() and (size is None or fixture.stat().st_size == size)
        assert OUT['digest'](fixture) == sha
    gradle = (ROOT / 'shared/build.gradle').read_text()
    for text in ('Frameworks/LinkaFFmpeg.framework', 'finalizedBy stageRuntime', 'mustRunAfter stageRuntime',
                 "'-linker-option=-rpath', '-linker-option=@executable_path/Frameworks'", 'FFmpeg definitions leaked',
                 "commandLine 'codesign', '--force', '--sign', '-', '--timestamp=none', destinationDir"):
        assert text in gradle
    if sys.platform == 'darwin':
        for relative in ('iosApp/LinkaPokazhi.xcodeproj/project.pbxproj', 'iosAppHost/LinkaKeychainHost.xcodeproj/project.pbxproj'):
            objects = json.loads(subprocess.check_output(['plutil', '-convert', 'json', '-o', '-', str(ROOT / relative)]))['objects']
            phases = [o for o in objects.values() if o.get('isa') == 'PBXCopyFilesBuildPhase']
            assert any(o.get('dstSubfolderSpec') == '10' for o in phases)
            for phase in phases:
                if phase.get('name') != 'Embed Frameworks':
                    continue
                assert phase['runOnlyForDeploymentPostprocessing'] == '0'
                for reference in phase['files']:
                    build = objects[reference]
                    assert set(build['settings']['ATTRIBUTES']) == {'CodeSignOnCopy', 'RemoveHeadersOnCopy'}
                    assert 'LinkaFFmpeg.framework' in objects[build['fileRef']]['path']
    print('PASS: vendored source, bridge-only def, privacy reasons and standard runtime staging contracts')


def build_proof(verified_root, output):
    output = OUT['output_root'](str(output))
    assert output.is_relative_to(ROOT / 'build/prerelease') and not output.exists()
    record = json.loads((verified_root / OUT['MANIFEST']).read_text())
    assert record['provenance']['source_archive_sha256'] == BUILDER['SOURCE_SHA']
    assert record['signature']['verified'] and record['signature']['fingerprint'] == BUILDER['PREFLIGHT']['FINGERPRINT']
    archive = ROOT / 'third_party/ffmpeg/ffmpeg-8.1.2.tar.xz'
    output.mkdir(parents=True)
    def public_verification(arguments):
        result = subprocess.run(list(map(str, arguments)), capture_output=True, text=True, check=True, timeout=30)
        return result.returncode, result.stdout + result.stderr
    task = SimpleNamespace(root=output, project=ROOT, result={}, run=public_verification)
    BUILDER['vendored_source'](task, extract=False)
    results = {}
    for target, (sdk, platform) in OUT['TARGETS'].items():
        OUT['flags_valid'](record['targets'][target])
        prefix = output / target
        (prefix / 'lib').mkdir(parents=True)
        for name in OUT['FFMPEG_LIBRARIES']:
            source = verified_root / target / 'lib' / name
            assert not source.is_symlink()
            assert OUT['digest'](source) == record['targets'][target]['libraries'][name]['sha256']
            OUT['native_metadata'](source, platform, os.environ)
            shutil.copyfile(source, prefix / 'lib' / name)
        OUT['build_runtime'](prefix, sdk, os.environ, archive)
        framework = prefix / OUT['RUNTIME']
        exports = OUT['command'](['xcrun', 'nm', '-g', '-U', framework / 'LinkaFFmpeg'], os.environ)
        for symbol in ('_avcodec_send_packet', '_avformat_open_input', '_av_malloc', '_swr_convert'):
            assert re.search(r'\b' + symbol + r'$', exports, re.M), symbol
        assert '_linka_audio_' not in exports
        with tarfile.open(archive) as source:
            for name in ('COPYING.LGPLv2.1', 'LICENSE.md'):
                assert (framework / name).read_bytes() == source.extractfile('ffmpeg-8.1.2/' + name).read()
        results[target] = dict(sha256=OUT['digest'](framework / 'LinkaFFmpeg'),
            install_name=OUT['INSTALL_NAME'], privacy=plistlib.loads((framework / 'PrivacyInfo.xcprivacy').read_bytes()),
            load_commands=OUT['command'](['xcrun', 'otool', '-l', framework / 'LinkaFFmpeg'], os.environ))
    (output / 'runtime-proof.json').write_text(json.dumps(dict(scope='direct runtime build from previously verified libraries; not final app proof',
        source_authentication=task.result['signature'], targets=results), indent=2) + '\n')
    print(f'PASS: direct unsigned device + simulator runtime build; {output / "runtime-proof.json"}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verified-root', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    check()
    if args.verified_root or args.output:
        assert args.verified_root and args.output
        build_proof(args.verified_root, args.output)
