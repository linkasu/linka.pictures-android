#!/usr/bin/env python3
"""Frozen external-only baseline Release → fixed Debug → fixed Release.

Default/--prepare prints a plan without subprocesses, inventory or writes.
Only --run executes; artifacts must already exist on mounted /Volumes/data.
--status /absolute/pipeline.json reads live per-stage matrix ETA; queued ETA is unknown.
No builds, retries, resume, release operations or automatic stop-marker removal.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import sys
import time

MATRIX = Path(__file__).resolve().with_name('test-ios-simulator-matrix.py')
STAGES = ('baseline-release', 'fixed-debug', 'fixed-release')
INPUTS = ('baseline_app', 'baseline_build_manifest', 'debug_app', 'debug_build_manifest',
          'release_app', 'release_build_manifest', 'debug_xctestrun', 'release_xctestrun')


def matrix_module():
    spec = importlib.util.spec_from_file_location('verification_matrix', MATRIX)
    module = importlib.util.module_from_spec(spec)
    previous = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = previous
    return module


def plan(args):
    stages = []
    for name, prefix, phase, configuration in (
            (STAGES[0], 'baseline', 'baseline', 'Release'),
            (STAGES[1], 'debug', 'fixed', 'Debug'),
            (STAGES[2], 'release', 'fixed', 'Release')):
        output = args.output_root / name
        command = [sys.executable, str(MATRIX), '--app', str(getattr(args, prefix + '_app')),
                   '--build-manifest', str(getattr(args, prefix + '_build_manifest')),
                    '--output', str(output), '--device-set', str(args.device_set),
                    '--delete-created',
                   '--phase', phase, '--config', configuration,
                   '--probe', 'launch' if phase == 'baseline' else 'xctest']
        if phase == 'fixed':
            command += ['--xctestrun', str(getattr(args, prefix + '_xctestrun'))]
        stages.append(dict(name=name, command=command, matrix_path=str(output / 'matrix.json'),
                           status='queued', eta_seconds=None, duration_seconds=None))
    return dict(status='prepared', output_root=str(args.output_root),
                stop_marker=str(args.output_root / 'matrix-stop.json'),
                eta_seconds=None, stages=stages)


def validate(args, matrix, frozen=False):
    for key in (*INPUTS, 'output_root', 'device_set'):
        path = getattr(args, key)
        matrix.external_path(path, '--' + key.replace('_', '-'))
        if frozen and key in INPUTS:
            if (key.endswith('_app') and (path.suffix != '.app' or not path.is_dir())
                    or not key.endswith('_app') and not path.is_file()):
                raise ValueError('Missing frozen artifact: ' + str(path))
    for path in (args.output_root, args.device_set):
        if path.exists() and not path.is_dir():
            raise ValueError('Expected directory: ' + str(path))
    output, devices = args.output_root.resolve(), args.device_set.resolve()
    if output == devices or output in devices.parents or devices in output.parents:
        raise ValueError('Output root and device set must not overlap')
    for key in INPUTS:
        artifact = getattr(args, key).resolve()
        for writable in (output, devices):
            if (artifact == writable or artifact in writable.parents
                    or writable in artifact.parents):
                raise ValueError('Writable storage overlaps frozen artifact: ' + str(artifact))
    if not frozen:
        return
    for prefix, configuration in (('baseline', 'Release'), ('debug', 'Debug'), ('release', 'Release')):
        app = getattr(args, prefix + '_app')
        info = plistlib.loads((app / 'Info.plist').read_bytes())
        if ('iPhoneSimulator' not in info.get('CFBundleSupportedPlatforms', [])
                or not (app / info['CFBundleExecutable']).is_file()):
            raise ValueError('Not a frozen simulator app: ' + str(app))
        sha = matrix.digest(app)
        matrix.configuration_provenance(info, configuration, sha,
                                        getattr(args, prefix + '_build_manifest'), required=True)
        if prefix != 'baseline':
            artifacts = matrix.test_artifacts(getattr(args, prefix + '_xctestrun'),
                                             info['CFBundleIdentifier'], sha)
            for artifact in artifacts:
                matrix.external_path(Path(artifact), 'test artifact')
                for writable in (output, devices):
                    resolved = Path(artifact).resolve()
                    if resolved == writable or resolved in writable.parents or writable in resolved.parents:
                        raise ValueError('Writable storage overlaps test artifact: ' + artifact)


def verify(stage, evidence, returncode, matrix):
    manifest, records = evidence['manifest'], evidence['records']
    selected = manifest['selected']
    if (manifest.get('scope') != 'full' or not selected or manifest.get('inventory') != selected
            or len(records) != len(selected) or [r.get('pair') for r in records] != selected
            or evidence.get('blocked_reason') or evidence.get('interrupted')):
        raise ValueError('Incomplete or blocked full matrix: ' + stage['name'])
    baseline = stage['name'] == STAGES[0]
    configuration = 'Debug' if stage['name'] == STAGES[1] else 'Release'
    if (manifest.get('phase') != ('baseline' if baseline else 'fixed')
            or manifest.get('configuration') != configuration
            or manifest.get('probe') != ('launch' if baseline else 'xctest')):
        raise ValueError('Wrong matrix phase/configuration/probe')
    if baseline:
        if (returncode != 1 or evidence.get('full_matrix_bug_reproduced') is not True
                or not all(r.get('status') in ('launch_alive', 'lifecycle_trap') for r in records)
                or not any(r.get('status') == 'lifecycle_trap' for r in records)):
            raise ValueError('Baseline did not reproduce the lifecycle trap across the full matrix')
    else:
        if returncode != 0 or evidence.get('outcome') != 'tests_passed':
            raise ValueError('Fixed matrix failed: ' + stage['name'])
        expected = matrix.test_batches('LinkaStartupUITests/MatrixSmokeTests')
        if len(expected) != 8 or manifest.get('test_batches') != expected:
            raise ValueError('Fixed matrix must select all eight smoke methods')
        for record in records:
            batches = record.get('test_batches', [])
            if (record.get('status') != 'tests_passed' or record.get('expected_test_count') != 8
                    or record.get('passed_test_count') != 8 or len(batches) != 8
                    or [b.get('batch') for b in batches] != expected
                    or not all(b.get('status') == 'tests_passed'
                               and matrix.tests_pass(b.get('summary', {}), 1) for b in batches)):
                raise ValueError('Fixed matrix lacks eight passing tests for every pair')


def execute_stage(command):
    # Forward interruption before bounded escalation; the matrix owns native-child cleanup.
    with subprocess.Popen(command, start_new_session=True) as child:
        try:
            return child.wait()
        except BaseException:
            for signum, timeout in ((signal.SIGINT, 20), (signal.SIGTERM, 5), (signal.SIGKILL, 5)):
                try:
                    os.killpg(child.pid, signum)
                except ProcessLookupError:
                    break
                try:
                    child.wait(timeout=timeout)
                    break
                except subprocess.TimeoutExpired:
                    continue
            raise


def run(args, state, matrix):
    root = args.output_root
    if (root / 'pipeline.json').exists() or any((root / name).exists() for name in STAGES):
        raise ValueError('Fresh output root required; pipeline does not resume or overwrite evidence')
    root.mkdir(parents=True, exist_ok=True)
    path = root / 'pipeline.json'
    state.update(status='running', started_at=datetime.now(timezone.utc).isoformat())
    matrix.atomic_json(path, state)
    for stage in state['stages']:
        started = time.monotonic()
        try:
            matrix.check_stop(Path(state['stop_marker']))
            stage.update(status='running', started_at=datetime.now(timezone.utc).isoformat())
            matrix.atomic_json(path, state)
            returncode = execute_stage(stage['command'])
            stage['returncode'] = returncode
            matrix.check_stop(Path(state['stop_marker']))
            evidence = json.loads(Path(stage['matrix_path']).read_text())
            verify(stage, evidence, returncode, matrix)
            stage.update(status='passed', eta_seconds=0)
        except (KeyboardInterrupt, OSError, ValueError, KeyError, TypeError, RuntimeError,
                subprocess.SubprocessError) as error:
            reason = str(error) or 'Interrupted by signal'
            stage.update(status='stopped', reason=reason, eta_seconds=None)
            state.update(status='stopped', failed_stage=stage['name'], reason=reason, eta_seconds=None)
            stage['duration_seconds'] = time.monotonic() - started
            try:
                matrix.latch_stop(Path(state['stop_marker']),
                                  dict(phase='baseline' if stage['name'] == STAGES[0] else 'fixed',
                                       configuration='Debug' if stage['name'] == STAGES[1] else 'Release'),
                                  dict(status='interrupted' if isinstance(error, KeyboardInterrupt)
                                       else 'pipeline_blocked', error=reason), stage['name'])
            except OSError as marker_error:
                state['stop_marker_error'] = str(marker_error)
            matrix.atomic_json(path, state)
            return 130 if isinstance(error, KeyboardInterrupt) else 1
        stage['duration_seconds'] = time.monotonic() - started
        matrix.atomic_json(path, state)
    state.update(status='passed', eta_seconds=0, finished_at=datetime.now(timezone.utc).isoformat())
    matrix.atomic_json(path, state)
    return 0


def status(path, matrix):
    matrix.external_path(path, '--status')
    state = json.loads(path.read_text())
    for stage in state['stages']:
        if stage['status'] == 'running':
            source = Path(stage['matrix_path'])
            matrix.external_path(source, 'matrix progress')
            try:
                stage['progress'] = matrix.progress(json.loads(source.read_text()), datetime.now(timezone.utc))
                stage['eta_seconds'] = stage['progress']['eta_seconds']
            except (OSError, ValueError, KeyError, TypeError) as error:
                stage.update(eta_seconds=None, progress_error=str(error))
        elif stage['status'] != 'passed':
            stage['eta_seconds'] = None
    if Path(state['stop_marker']).exists():
        state.update(status='stopped', eta_seconds=None, reason='Persistent matrix stop marker exists')
        for stage in state['stages']:
            if stage['status'] == 'running':
                stage.update(status='stopped', eta_seconds=None)
    return state


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--prepare', action='store_true', help='print JSON plan only (default); no writes or subprocesses')
    mode.add_argument('--run', action='store_true', help='explicitly execute the frozen three-stage queue')
    mode.add_argument('--status', type=Path, help='read-only absolute pipeline.json with live per-stage ETA')
    for key in (*INPUTS, 'output_root', 'device_set'):
        parser.add_argument('--' + key.replace('_', '-'), type=Path)
    args = parser.parse_args(argv)
    matrix = matrix_module()
    if args.status:
        print(json.dumps(status(args.status, matrix), indent=2))
        return 0
    if not all(getattr(args, key) for key in (*INPUTS, 'output_root', 'device_set')):
        parser.error('all frozen artifact paths, --output-root and --device-set are required; see --help')
    validate(args, matrix, frozen=args.run)
    state = plan(args)
    if not args.run:
        print(json.dumps(state, indent=2))
        return 0
    return run(args, state, matrix)


if __name__ == '__main__':
    def interrupt(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        sys.exit(str(error))
