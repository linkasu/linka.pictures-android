#!/usr/bin/env python3
"""The cinterop def is consumed by ld, not clang; preserve only the known legacy upgrade."""
from pathlib import Path
import runpy

module = runpy.run_path(str(Path(__file__).with_name('ios_audio_decoder_outputs.py')))
prefix = Path('/generated/build/decoder/iosSimulatorArm64')
definition = module['definition'](prefix)
assert '-pthread' not in definition
assert module['current_definition'](definition, prefix)
legacy = definition.replace('linkerOpts = -lm ', 'linkerOpts = -lm -pthread ')
assert not module['current_definition'](legacy, prefix)
for altered in (definition + 'linkerOpts = -foreign\n', definition.replace(str(prefix), '/foreign/build/path')):
    try:
        module['current_definition'](altered, prefix)
    except RuntimeError:
        pass
    else:
        raise AssertionError('Unknown output definition was accepted')
print('decoder def checks PASS')
