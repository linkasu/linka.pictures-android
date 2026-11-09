#!/usr/bin/env python3
"""The cinterop def is consumed by ld, not clang; preserve only the known legacy upgrade."""
from pathlib import Path
import runpy

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
