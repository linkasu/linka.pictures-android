#!/usr/bin/env python3
"""Read-only seed + owned generated clones: actual producer CLI refusal/fastpath/update checks."""
import argparse
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--published-root', required=True)
    parser.add_argument('--reuse-build-run', required=True, help='owned completed run to verify explicit seeding')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    builder = runpy.run_path(str(project / 'scripts/build-ios-audio-decoder.py'))
    outputs = builder['OUTPUTS']
    seed = outputs['output_root'](args.published_root)
    env = dict(os.environ, DEVELOPER_DIR=builder['PREFLIGHT']['DEVELOPER'])
    _, valid = outputs['inspect'](seed, project, builder['FLAGS'], env)
    assert valid, 'explicit seed must be current'
    base = Path(tempfile.mkdtemp(prefix='producer-selfcheck-', dir=project / 'build/ios-ffmpeg-preflight'))
    results = {}

    def snapshot(root):
        return {str(p.relative_to(root)): (p.stat().st_mtime_ns, outputs['digest'](p))
                for p in root.rglob('*') if p.is_file()}

    def clone(name):
        root = base / name
        shutil.copytree(seed, root)
        record = json.loads((root / outputs['MANIFEST']).read_text())
        record['root'] = str(root)
        for target in outputs['TARGETS']:
            (root / target / 'linka_audio_decoder.def').write_text(outputs['definition'](root / target))
        record['files'] = outputs['inventory'](root)
        (root / outputs['MANIFEST']).write_text(json.dumps(record))
        return root

    def invoke(root, expected):
        before = snapshot(root)
        runs = set((project / 'build/ios-ffmpeg-preflight').iterdir())
        result = subprocess.run([sys.executable, '-B', project / 'scripts/build-ios-audio-decoder.py',
                                 '--gradle-output-root', root], capture_output=True, text=True, timeout=180)
        assert (result.returncode == 0) == expected, result.stdout + result.stderr
        if not expected or 'VERIFIED_FASTPATH' in result.stdout:
            assert before == snapshot(root), 'refusal/fastpath must not touch outputs'
            assert runs == set((project / 'build/ios-ffmpeg-preflight').iterdir()), 'unexpected new build run'
        return result.stdout.strip()

    root = clone('fastpath')
    results['fastpath'] = invoke(root, True)
    assert 'VERIFIED_FASTPATH' in results['fastpath']
    # Trap all writes/build/runtime entry points in-process, in addition to checking disk mtimes.
    def forbidden(*args, **kwargs):
        raise AssertionError('exact fastpath attempted construction/copy/build/runtime')
    old_argv = sys.argv
    old_copytree, old_copyfile = shutil.copytree, shutil.copyfile
    try:
        builder['main'].__globals__['Preflight'] = forbidden
        shutil.copytree = shutil.copyfile = forbidden
        sys.argv = ['producer', '--gradle-output-root', str(root)]
        builder['main']()
    finally:
        sys.argv = old_argv
        shutil.copytree, shutil.copyfile = old_copytree, old_copyfile
    results['fastpath_no_build_or_copy'] = 'PASS'

    root = base / 'unknown'
    root.mkdir()
    (root / 'sentinel').write_text('foreign')
    invoke(root, False)
    results['unknown_nonempty'] = 'PASS'
    mutations = {
        'header': lambda r: (r / 'iosArm64/include/linka_audio_decoder.h').write_text('tampered'),
        'library': lambda r: (r / 'iosArm64/lib/libavutil.a').write_bytes(b'tampered'),
        'def': lambda r: (r / 'iosArm64/linka_audio_decoder.def').write_text('tampered'),
        'extra-directory': lambda r: (r / 'unexpected').mkdir(),
    }
    for name, mutate in mutations.items():
        root = clone(name)
        mutate(root)
        invoke(root, False)
        results[name] = 'PASS'
    for field in ('config_text', 'components_text', 'make_config_text'):
        root = clone(field)
        manifest = root / outputs['MANIFEST']
        record = json.loads(manifest.read_text())
        record['targets']['iosArm64'][field] += '\nchanged\n'
        manifest.write_text(json.dumps(record))
        invoke(root, False)
        results[field] = 'PASS'
    root = clone('wrong-platform')
    shutil.copyfile(root / 'iosArm64/lib/liblinka_audio_decoder.a', root / 'iosSimulatorArm64/lib/liblinka_audio_decoder.a')
    manifest = root / outputs['MANIFEST']
    record = json.loads(manifest.read_text())
    record['files'] = outputs['inventory'](root)  # Exercise Mach-O verification, not just hash mismatch.
    record['targets']['iosSimulatorArm64']['libraries']['liblinka_audio_decoder.a']['sha256'] = outputs['digest'](root / 'iosSimulatorArm64/lib/liblinka_audio_decoder.a')
    manifest.write_text(json.dumps(record))
    invoke(root, False)
    results['wrong_platform_matching_inventory'] = 'PASS'

    # Same real tar verifier used by --reuse-build-run: no extraction and no fake headers.
    root = clone('cached-headers')
    record = json.loads((root / outputs['MANIFEST']).read_text())
    archive = next(Path(x['path']) for x in record['source_downloads'] if x['url'] == builder['PREFLIGHT']['SOURCE'])
    outputs['verify_headers'](root / 'iosArm64', archive)
    (root / 'iosArm64/include/libavutil/avutil.h').write_text('tampered cached header')
    try:
        outputs['verify_headers'](root / 'iosArm64', archive)
    except RuntimeError:
        pass
    else:
        raise AssertionError('tampered cached FFmpeg header accepted')
    results['cached_headers_tar_match_and_tamper_refusal'] = 'PASS'

    root = base / 'explicit-seed'
    result = subprocess.run([sys.executable, '-B', project / 'scripts/build-ios-audio-decoder.py',
                             '--reuse-build-run', args.reuse_build_run,
                             '--gradle-output-root', root], capture_output=True, text=True, timeout=180)
    assert result.returncode == 0, result.stdout + result.stderr
    record = json.loads((root / outputs['MANIFEST']).read_text())
    commands = json.loads((Path(record['artifact_run']) / 'result.json').read_text())['commands']
    assert all('-c' not in x['command'] and not any(y in ('make', 'spawn') for y in x['command']) for x in commands)
    results['explicit_seed_unchanged_bridge_not_recompiled'] = 'PASS'
    assert 'VERIFIED_FASTPATH' in invoke(root, True)

    root = clone('stale-c')
    manifest = root / outputs['MANIFEST']
    record = json.loads(manifest.read_text())
    record['provenance']['files']['shared/src/nativeInterop/c/linka_audio_decoder.c'] = '0' * 64
    for target in record['targets'].values():
        target['bridge_source_sha256'] = '0' * 64
        target['bridge_inputs_sha256']['shared/src/nativeInterop/c/linka_audio_decoder.c'] = '0' * 64
    manifest.write_text(json.dumps(record))
    results['stale_c_update'] = invoke(root, True)
    record = json.loads(manifest.read_text())
    run = Path(record['artifact_run'])
    commands = json.loads((run / 'result.json').read_text())['commands']
    compiled = []
    for item in commands:
        command = item['command']
        assert not any(x in ('make', 'configure', 'spawn', 'boot') for x in command), command
        if '-c' in command:
            compiled.append(command)
    assert len(compiled) == 2 and all(any(x.endswith('/linka_audio_decoder.c') for x in cmd) for cmd in compiled)
    for target in outputs['TARGETS']:
        for lib in ('libavformat.a', 'libavcodec.a', 'libavutil.a', 'libswresample.a'):
            assert outputs['digest'](root / target / 'lib' / lib) == outputs['digest'](seed / target / 'lib' / lib)
    results['stale_c_bridge_sources_recompiled_no_ffmpeg_or_runtime_execution'] = 'PASS'
    assert 'VERIFIED_FASTPATH' in invoke(root, True)
    results['updated_fastpath'] = 'PASS'
    (base / 'result.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps({'status': 'PASS', 'checks': len(results), 'evidence': str(base / 'result.json')}))


if __name__ == '__main__':
    main()
