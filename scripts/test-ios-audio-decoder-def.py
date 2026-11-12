#!/usr/bin/env python3
"""The cinterop def is consumed by ld, not clang; preserve only the known legacy upgrade."""
from pathlib import Path
import os
import runpy
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import Mock, patch

module = runpy.run_path(str(Path(__file__).with_name('ios_audio_decoder_outputs.py')))
prefix = Path('/generated/build/decoder/iosSimulatorArm64')
definition = module['definition'](prefix)
assert '-pthread' not in definition
assert 'staticLibraries = liblinka_audio_decoder.a\n' in definition
assert '-framework LinkaFFmpeg' in definition and '-framework AudioToolbox' in definition
assert 'libavcodec.a' not in definition and 'libavformat.a' not in definition
assert module['current_definition'](definition, prefix)
legacy = definition.replace('staticLibraries = liblinka_audio_decoder.a\n',
    'staticLibraries = liblinka_audio_decoder.a libavformat.a libavcodec.a libswresample.a libavutil.a\n')
legacy = legacy.replace(f'-F{prefix} -framework LinkaFFmpeg ', '').replace(' -framework AudioToolbox', '')
assert not module['current_definition'](legacy, prefix)
assert not module['current_definition'](legacy.replace('linkerOpts = -lm ', 'linkerOpts = -lm -pthread '), prefix)
for altered in (definition + 'linkerOpts = -foreign\n', definition.replace(str(prefix), '/foreign/build/path')):
    try:
        module['current_definition'](altered, prefix)
    except RuntimeError:
        pass
    else:
        raise AssertionError('Unknown output definition was accepted')
print('decoder def checks PASS')

with patch.object(subprocess, 'run', side_effect=AssertionError('import executed a tool')):
    builder = runpy.run_path(str(Path(__file__).with_name('build-ios-audio-decoder.py')))
preflight = builder['PREFLIGHT']
with patch.dict(os.environ, {'DEVELOPER_DIR': '/selected/Xcode/Contents/Developer'}, clear=True), \
        patch.object(subprocess, 'run', side_effect=AssertionError('explicit Xcode was ignored')):
    assert preflight['developer_environment']()['DEVELOPER_DIR'] == '/selected/Xcode/Contents/Developer'
with patch.dict(os.environ, {}, clear=True), \
        patch.object(subprocess, 'run', return_value=SimpleNamespace(stdout='/runner/Xcode/Contents/Developer\n')) as selected:
    assert preflight['developer_environment']()['DEVELOPER_DIR'] == '/runner/Xcode/Contents/Developer'
    assert selected.call_args.args[0] == ['xcode-select', '-p']
with patch.dict(os.environ, {}, clear=True), \
        patch.object(preflight['tempfile'], 'gettempdir', return_value='/runner/tmp'):
    assert preflight['work_root'](Path('/runner/project')) == Path('/runner/tmp/linka-ios-ffmpeg-preflight')
    assert preflight['work_root'](Path('/Volumes/data/linka/project')) == Path('/Volumes/data/linka/project/build/ios-ffmpeg-preflight')
with patch.dict(os.environ, {'LINKA_FFMPEG_WORK_ROOT': '/runner/work/ffmpeg'}, clear=True):
    assert preflight['work_root'](Path('/Volumes/data/linka/project')) == Path('/runner/work/ffmpeg')

def fake_command(args, env):
    if args == ['xcodebuild', '-version']:
        return xcode
    return '/runner/tool' if '--find' in args or '--show-sdk-path' in args else '26.0\n'

with patch.dict(module['toolchain'].__globals__, command=fake_command):
    for xcode in ('Xcode 26.0\nBuild version 17A1\n', 'Xcode 27.2\nBuild version 18C1\n'):
        chain = module['toolchain']({})
        assert chain['xcode'] == xcode
        assert set(chain) == {'xcode', 'iphoneos', 'iphonesimulator'}
    previous = chain
    xcode = 'Xcode 27.2\nBuild version 18C2\n'
    assert module['toolchain']({}) != previous, 'Xcode build change must invalidate the cache'
    for xcode in ('Xcode 25.4\nBuild version old\n', 'unknown'):
        try:
            module['toolchain']({})
        except RuntimeError:
            pass
        else:
            raise AssertionError('unsupported toolchain accepted')

# Execute the real default build orchestration, trapping every proof/fixture entry point.
def forbidden(*args, **kwargs):
    raise AssertionError('build-only path touched fixtures, proof, or unmocked external tools')

task = SimpleNamespace(root=Path('/runner/build/owned'), env={}, originals={}, result={}, save=Mock())
def source(task):
    task.result['downloads'] = [{'url': preflight['SOURCE'], 'sha256': builder['SOURCE_SHA']}]

build_target = Mock(return_value=(Path('/runner/build/prefix'), Path('/runner/build/test')))
with patch.dict(builder['main'].__globals__, Preflight=lambda: task, vendored_source=source,
                target=build_target, bridge_fixtures=forbidden, proof=forbidden, scoped_kotlin=forbidden), \
        patch.dict(builder['OUTPUTS'], current_sources=Mock(), toolchain=lambda env: previous), \
        patch.object(subprocess, 'run', side_effect=forbidden), \
        patch.object(sys, 'argv', ['producer']):
    builder['main']()
assert task.result['status'] == 'BUILD_ONLY' and task.result['toolchain'] == previous
assert task.result['original_fixture_files_verified'] == 0
assert [call.args[1] for call in build_target.call_args_list] == ['iphonesimulator', 'iphoneos']
print('decoder producer portability/build-only checks PASS')
