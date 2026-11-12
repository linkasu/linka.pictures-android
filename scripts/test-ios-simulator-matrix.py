#!/usr/bin/env python3
"""Sequential, owned-device iOS matrix; never builds, releases, erases or retries.

Inventory: --list
Read-only live ETA: --progress /absolute/evidence/matrix.json
ETA covers this matrix's whole pairs, not queued pipeline stages or guaranteed runtime speeds.
Run: --app /Volumes/data/Frozen.app --output /Volumes/data/evidence --phase fixed
     --device-set /Volumes/data/matrix-devices --config Debug
     --xctestrun /Volumes/data/Smoke.xctestrun
Launch evidence only: replace --xctestrun with --probe launch.
Required external storage: --device-set /Volumes/data/matrix-devices
All frozen inputs and evidence must reside on mounted /Volumes/data.
--validate-only checks inputs without inventory, writes or execution; pair count remains unknown.
XCTest custom-set execution still needs a future real pilot; host OS caches may remain internal.
First unexpected result stops this queue and latches matrix-stop.json in the output parent.
Requires 5 GiB free on evidence and device-set filesystems before each pair.
--only-runtime and --limit are pilots, never full-matrix evidence.
"""
import argparse
from contextlib import contextmanager, nullcontext
import fcntl
from datetime import datetime, timedelta, timezone
import errno
import hashlib
import json
import math
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time
import uuid

LIFECYCLE = 'UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption'
DEVICE_SET = None
CHILD_TMPDIR = None
MIN_FREE_BYTES = 5 * 1024 ** 3
EDITOR = 'testZZEditorCreatePublishRelaunchAndCancelCardDraft'
LARGE_TEXT = 'testLargeTextLibraryAndCommunicationHitTargets'
LARGE_CATEGORY = 'accessibility-extra-extra-extra-large'
METHODS = ('testColdLaunchAndRelaunch', 'testEnglishAndRussianRelaunch',
           'testOriginalCommunicationFirstPageDecodedAndPaging',
           'testSelectionBackgroundPersistenceRelaunchAndClear',
           'testOrientationCardBoundsAndHitTargets', 'testLargeTextLibraryAndCommunicationHitTargets',
           'testSettingsOpenAndBackWithoutWrites', EDITOR)


def command(arguments, timeout=60, log=None, env_overrides=None):
    arguments = list(map(str, arguments))
    if env_overrides and (env_overrides != {'SIMCTL_CHILD_OS_ACTIVITY_DT_MODE': 'YES'}
                          or arguments[:3] != ['xcrun', 'simctl', 'launch']):
        raise ValueError('Only the launch-scoped OS_ACTIVITY_DT_MODE override is allowed')
    if DEVICE_SET is not None and arguments[:2] == ['xcrun', 'simctl']:
        arguments[2:2] = ['--set', str(DEVICE_SET)]
    if DEVICE_SET is not None and arguments[0] == 'xcodebuild':
        arguments.insert(1, '-DVTSimulatorSetLocation=' + str(DEVICE_SET))
    env = dict(os.environ)
    if CHILD_TMPDIR is not None:
        env['TMPDIR'] = str(CHILD_TMPDIR)
    env.update(env_overrides or {})
    # A separate process group also bounds xcodebuild's child processes.
    with subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           start_new_session=True, env=env) as process:
        try:
            data, _ = process.communicate(timeout=timeout)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                data, _ = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                data, _ = process.communicate(timeout=5)
            if log:
                Path(log).write_bytes(data)
            raise
    text = data.decode('utf-8', errors='replace')
    if log:
        Path(log).write_text(text)
    if process.returncode:
        raise RuntimeError(f'command failed ({process.returncode}): {arguments[0:3]}')
    return text


def device_root():
    return DEVICE_SET if DEVICE_SET is not None else Path.home() / 'Library/Developer/CoreSimulator/Devices'


def child_tmpdir(output):
    # macOS Unix sockets have a short path limit; evidence paths may be arbitrarily deep.
    name = hashlib.sha256(os.fsencode(output.resolve())).hexdigest()[:16]
    return Path('/Volumes/data/.linka-tmp') / name


class DiskSpaceBlocked(RuntimeError):
    pass


def disk_preflight(output):
    # CoreSimulator also writes host caches on the system volume, even with --set.
    for destination in (output, device_root(), Path('/System/Volumes/Data')):
        existing = destination.resolve()
        while not existing.exists():
            existing = existing.parent
        free = shutil.disk_usage(existing).free
        if free < MIN_FREE_BYTES:
            raise DiskSpaceBlocked(f'Disk space blocked: {destination}: {free} bytes free; need at least {MIN_FREE_BYTES}')


def inventory():
    return matrix(json.loads(command(['xcrun', 'simctl', 'list', '-j', 'runtimes'])))


def matrix(data):
    pairs = {}
    for runtime in data['runtimes']:
        if not runtime.get('isAvailable') or runtime.get('platform') != 'iOS':
            continue
        for device in runtime.get('supportedDeviceTypes', []):
            if device['name'].startswith(('iPhone', 'iPad')):
                key = (runtime['identifier'], device['identifier'])
                pairs[key] = dict(runtime=key[0], device_type=key[1],
                                  runtime_name=runtime['name'], runtime_build=runtime.get('buildversion'),
                                  device_name=device['name'])
    if not pairs:
        raise ValueError('No available iOS iPhone/iPad pairs in simctl inventory')
    return [pairs[key] for key in sorted(pairs)]


def digest(path):
    result = hashlib.sha256()
    paths = sorted(path.rglob('*')) if path.is_dir() else [path]
    for item in paths:
        relative = str(item.relative_to(path)) if path.is_dir() else item.name
        result.update(relative.encode() + b'\0')
        if item.is_symlink():
            result.update(b'link\0' + os.readlink(item).encode())
        elif item.is_file():
            with item.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    result.update(block)
        result.update(b'\0')
    return result.hexdigest()


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    os.replace(temporary, path)


def validate_resume(saved, manifest):
    if saved['manifest'] != manifest:
        raise ValueError('Resume refused: phase/config/app SHA, test inputs or frozen inventory differ')


def external_path(path, label):
    root = Path('/Volumes/data')
    if (not root.is_mount() or root.resolve() != root
            or root.stat().st_dev == Path('/').stat().st_dev):
        raise ValueError('Mounted external /Volumes/data is required; no internal fallback')
    if not path.is_absolute() or not path.resolve().is_relative_to(root):
        raise ValueError(label + ' must resolve beneath mounted /Volumes/data')
    if path.is_dir() and label in ('--app', 'test artifact'):
        for link in path.rglob('*'):
            if link.is_symlink() and not link.resolve().is_relative_to(root):
                raise ValueError(label + ' contains a symlink outside /Volumes/data: ' + str(link))
    return path.resolve()


class QueueStopped(RuntimeError):
    pass


def check_stop(path):
    if path.exists():
        raise QueueStopped('Queue stopped by persistent marker ' + str(path) + ': ' + path.read_text())


def unexpected(manifest, status):
    if manifest.get('probe') == 'xctest':
        return status != 'tests_passed'
    return status not in ('tests_passed', 'launch_alive') and not (
        manifest.get('phase') == 'baseline' and manifest.get('probe') == 'launch' and status == 'lifecycle_trap')


def latch_stop(path, manifest, record, stage):
    cause = dict(recorded_at=datetime.now(timezone.utc).isoformat(), phase=manifest.get('phase'),
                 configuration=manifest.get('configuration'), stage=stage, sourcepair=record.get('pair'),
                 status=record['status'], error=record.get('error'), created_udid=record.get('created_udid'))
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, prefix='.matrix-stop-', delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(cause, stream, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)
    except FileExistsError:
        pass
    finally:
        if temporary is not None:
            temporary.unlink()


def progress(state, now):
    def timestamp(value):
        try:
            result = datetime.fromisoformat(value)
            return result if result.tzinfo is not None else None
        except (ValueError, TypeError):
            return None

    def interval(start, end):
        start, end = timestamp(start), timestamp(end)
        return (end - start).total_seconds() if start is not None and end is not None else None

    records = state.get('records', [])
    manifest = state['manifest']
    counts = {}
    for record in records:
        status = record.get('status', 'pending')
        counts[status] = counts.get(status, 0) + 1
    terminal = {'launch_alive', 'lifecycle_trap', 'unknown_crash', 'tests_passed', 'tests_failed',
                'infrastructure_blocked', 'capability_blocked', 'disk_space_blocked', 'interrupted', 'queue_stopped'}
    blocked = {'infrastructure_blocked', 'capability_blocked', 'disk_space_blocked', 'interrupted', 'queue_stopped'}
    total = len(manifest['selected'])
    processed = sum(count for status, count in counts.items() if status in terminal)
    remaining = max(0, total - processed)
    active = next((r for r in records if r.get('status') == 'running'), None)
    samples, sources = [], []
    for index, record in enumerate(records):
        if record.get('status') not in terminal:
            break
        if record['status'] in blocked:
            continue
        duration = record.get('duration_seconds')
        source = 'duration_seconds'
        if 'duration_seconds' not in record:
            duration = interval(record.get('started_at'), record.get('finished_at'))
            source = 'finished_at'
            if duration is None and index + 1 < len(records):
                duration = interval(record.get('started_at'), records[index + 1].get('started_at'))
                source = 'adjacent_started_at'
        if type(duration) in (int, float) and math.isfinite(duration) and duration > 0:
            samples.append(duration)
            sources.append(source)
    samples, sources = samples[-20:], sources[-20:]
    median = statistics.median(samples) if samples else None
    halted = bool(state.get('blocked_reason') or state.get('interrupted')
                  or (records and records[-1].get('status') in blocked and active is None))
    eta, spread, finish = None, None, None
    basis = 'median of recent <=20 completed nonblocked pairs; ' + ', '.join(sorted(set(sources)))
    if halted:
        basis += '; halted: no completion ETA until resumed'
    elif not remaining:
        eta, spread = 0, [0, 0]
    elif len(samples) < 3:
        basis += '; insufficient samples (need 3)'
    else:
        quartiles = statistics.quantiles(samples, n=4, method='inclusive')
        eta = median * remaining
        spread = [quartiles[0] * remaining, quartiles[2] * remaining]
    if eta is not None:
        try:
            finish = (now + timedelta(seconds=eta)).isoformat()
        except OverflowError:
            pass
    start = timestamp(state.get('started_at'))
    if start is None and records:
        start = timestamp(records[0].get('started_at'))
    elapsed = (now - start).total_seconds() if start is not None else None
    return dict(processed=processed, total=total, remaining=remaining, status_counts=counts,
                blocked=sum(counts.get(status, 0) for status in blocked),
                blocked_remaining=remaining if halted else 0,
                activepair=active.get('pair') if active else None,
                phase=manifest.get('phase'), probe=manifest.get('probe'),
                sample_count=len(samples), mean_seconds=statistics.fmean(samples) if samples else None,
                median_seconds=median, eta_seconds=eta, eta_range_seconds=spread,
                estimated_finishISO=finish, estimatebasis=basis,
                estimate_scope='observed whole-pair timings only; runtime speeds may differ; not queued stages',
                elapsed_seconds=elapsed if elapsed is not None and elapsed >= 0 else None)


def tests_pass(summary, expected=None):
    return (type(summary.get('passedTests')) is int and summary['passedTests'] > 0
            and (expected is None or summary['passedTests'] == expected)
            and type(summary.get('totalTestCount')) is int
            and summary['totalTestCount'] == summary['passedTests']
            and type(summary.get('failedTests')) is int and summary['failedTests'] == 0
            and type(summary.get('skippedTests')) is int and summary['skippedTests'] == 0
            and summary.get('testFailures', []) == []
            and summary.get('result') == 'Passed')


def baseline_reproduced(manifest, records):
    return (manifest['phase'] == 'baseline' and manifest['probe'] == 'launch'
            and manifest['scope'] == 'full' and len(records) == len(manifest['selected'])
            and all(r['status'] in ('launch_alive', 'lifecycle_trap') for r in records)
            and any(r['status'] == 'lifecycle_trap' for r in records))


def test_batches(selector):
    parts = selector.split('/')
    if (len(parts) not in (2, 3) or parts[:2] != ['LinkaStartupUITests', 'MatrixSmokeTests']
            or (len(parts) == 3 and parts[2] not in METHODS)):
        raise ValueError('Selector must name LinkaStartupUITests/MatrixSmokeTests or an approved method')
    if len(parts) == 3:
        return [dict(name='smoke', selector=selector, expected=1, skip=None)]
    return [dict(name=method, selector=selector + '/' + method, expected=1, skip=None) for method in METHODS]


class CapabilityBlocked(RuntimeError):
    pass


@contextmanager
def large_text_category(udid, evidence, folder):
    def setting(value=None, name='query'):
        arguments = ['xcrun', 'simctl', 'ui', udid, 'content_size']
        if value is not None:
            arguments.append(value)
        try:
            return command(arguments, timeout=30, log=folder / ('content-size-' + name + '.log')).strip()
        except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
            raise CapabilityBlocked('Native content_size capability blocked: ' + str(error)) from error

    original = setting(name='original')
    categories = ('extra-small', 'small', 'medium', 'large', 'extra-large', 'extra-extra-large',
                  'extra-extra-extra-large', 'accessibility-medium', 'accessibility-large',
                  'accessibility-extra-large', 'accessibility-extra-extra-large', LARGE_CATEGORY)
    evidence['content_size'] = dict(original=original, requested=LARGE_CATEGORY,
                                   scope='OS category readback; Compose effective font scale is not directly measured')
    if original not in categories:
        raise CapabilityBlocked('Native content_size returned an unsupported original label')
    try:
        setting(LARGE_CATEGORY, 'set')
        observed = setting(name='observed')
        evidence['content_size']['observed'] = observed
        if observed != LARGE_CATEGORY:
            raise CapabilityBlocked('Native content_size requested/observed mismatch')
        yield
    finally:
        setting(original, 'restore')
        restored = setting(name='restored')
        evidence['content_size']['restored'] = restored
        if restored != original:
            raise CapabilityBlocked('Native content_size restoration readback mismatch')


def configuration_provenance(info, configuration, sha, build_manifest, required):
    recorded = info.get('LinkaBuildConfiguration') or info.get('Configuration') or info.get('BuildConfiguration')
    if recorded and recorded != configuration:
        raise ValueError('App Info.plist configuration differs from --config')
    if build_manifest:
        evidence = json.loads(build_manifest.read_text())
        if evidence.get('configuration') != configuration or evidence.get('app_sha256') != sha:
            raise ValueError('Build manifest configuration/app SHA mismatch')
        return dict(source='build_manifest', path=str(build_manifest.resolve()), sha256=digest(build_manifest))
    if recorded:
        return dict(source='Info.plist', configuration=recorded)
    if required:
        raise ValueError('XCTest requires Info.plist LinkaBuildConfiguration or --build-manifest with configuration/app_sha256')
    return dict(source='declared_only', note='Launch evidence does not verify build configuration')


def test_artifacts(path, bundle, app_sha, require_external=False):
    data = plistlib.loads(path.read_bytes())
    if 'TestConfigurations' in data:
        targets = [target for config in data['TestConfigurations'] if config.get('IsEnabled', True)
                   for target in config.get('TestTargets', [])
                   if target.get('BlueprintName', target.get('TargetName')) == 'LinkaStartupUITests']
    else:
        targets = [data['LinkaStartupUITests']] if 'LinkaStartupUITests' in data else []
    if not targets:
        raise ValueError('xctestrun has no selected LinkaStartupUITests target')
    artifacts = {}
    for target in targets:
        if target.get('TestingEnvironmentVariables', {}).get('LINKA_UI_PRODUCT_BUNDLE_ID') != bundle:
            raise ValueError('xctestrun TestingEnvironmentVariables requires matching LINKA_UI_PRODUCT_BUNDLE_ID')
        environments = [target[key] for key in ('TestingEnvironmentVariables', 'EnvironmentVariables')
                        if 'LINKA_UI_PRODUCT_BUNDLE_ID' in target.get(key, {})]
        if not environments or any(env['LINKA_UI_PRODUCT_BUNDLE_ID'] != bundle for env in environments):
            raise ValueError('xctestrun requires explicit matching LINKA_UI_PRODUCT_BUNDLE_ID')

        def resolve(value, host=None):
            value = value.replace('__TESTROOT__', str(path.parent))
            if host:
                value = value.replace('__TESTHOST__', str(host))
            if '__PLATFORMS__' in value:
                developer = os.environ.get('DEVELOPER_DIR')
                if not developer:
                    raise ValueError('Freeze __PLATFORMS__ artifacts externally or supply DEVELOPER_DIR')
                value = value.replace('__PLATFORMS__', str(Path(developer) / 'Platforms'))
            if '__' in value:
                raise ValueError('Unresolved xctestrun path placeholder: ' + value)
            result = Path(value)
            if not result.is_absolute() or not result.exists():
                raise ValueError('Missing/nonabsolute xctestrun artifact: ' + value)
            if require_external:
                external_path(result, 'test artifact')
            return result.resolve()

        if not target.get('TestHostPath') or not target.get('TestBundlePath'):
            raise ValueError('xctestrun must identify TestHostPath and TestBundlePath')
        host = resolve(target['TestHostPath'])
        test = resolve(target['TestBundlePath'], host)
        for artifact in (host, test):
            # Freeze the full runner app even if TestHostPath names its executable.
            root = next((p for p in (artifact, *artifact.parents) if p.suffix == '.app'), artifact)
            artifacts[str(root)] = digest(root)
        if target.get('UITargetAppPath'):
            product = resolve(target['UITargetAppPath'], host)
            if digest(product) != app_sha:
                raise ValueError('xctestrun UITargetAppPath differs from frozen app')
            artifacts[str(product)] = app_sha
    return artifacts


def verify_test_inputs(args, manifest):
    external_path(args.xctestrun, '--xctestrun')
    if digest(args.xctestrun) != manifest['xctestrun_sha256']:
        raise RuntimeError('Frozen xctestrun changed during matrix')
    for path, sha in manifest['test_artifact_hashes'].items():
        external_path(Path(path), 'test artifact')
        if digest(Path(path)) != sha:
            raise RuntimeError('Frozen test artifact changed: ' + path)


def classify(text, crashed=False):
    if LIFECYCLE in text:
        return 'lifecycle_trap'
    return 'unknown_crash' if crashed else 'infrastructure_blocked'


def launch_console(folder):
    # At most 2 MiB across these two owned files, retaining both startup and tail.
    evidence = []
    half = 512 * 1024
    for name in ('stdout.log', 'stderr.log'):
        try:
            with (folder / name).open('rb') as stream:
                first = stream.read(half)
                size = os.fstat(stream.fileno()).st_size
                stream.seek(max(half, size - half))
                evidence.append((first + stream.read(half)).decode('utf-8', errors='replace'))
        except FileNotFoundError:
            pass
    return '\n'.join(evidence)


def crash_evidence(udid, bundle, executable, started, folder):
    if LIFECYCLE in launch_console(folder):
        return 'lifecycle_trap'
    # Only this owned simulator: unrelated host crash reports may contain private data.
    root = device_root() / udid / 'data/Library/Logs/CrashReporter'
    evidence = []
    paths = list(root.glob('**/*.ips'))
    for path in paths:
        if path.stat().st_mtime < started or not path.name.startswith(executable):
            continue
        text = path.read_text(errors='replace')
        if bundle not in text:
            continue
        (folder / path.name).write_text(text)
        evidence.append(text)
    stamp = datetime.fromtimestamp(started, timezone.utc).strftime('%Y-%m-%d %H:%M:%S%z')
    # Restrict simulator log output to the known assertion, not arbitrary app logs.
    try:
        assertion = command(['xcrun', 'simctl', 'spawn', udid, 'log', 'show', '--style', 'compact',
                             '--start', stamp, '--predicate',
                             f'process == "{executable}" AND eventMessage CONTAINS "{LIFECYCLE}"'],
                            timeout=5, log=folder / 'lifecycle.log')
        evidence.append(assertion)
    except (RuntimeError, subprocess.TimeoutExpired):
        pass
    return classify('\n'.join(evidence), crashed=any(bundle in item for item in evidence))


class LaunchCrashed(RuntimeError):
    def __init__(self, message, pid):
        super().__init__(message)
        self.pid = pid


class LaunchCommandFailed(RuntimeError):
    pass


def launch_probe(udid, bundle, executable, duration, folder):
    try:
        launched = command(['xcrun', 'simctl', 'launch', '--stdout=' + str(folder / 'stdout.log'),
                            '--stderr=' + str(folder / 'stderr.log'), udid, bundle], log=folder / 'launch.log',
                           env_overrides={'SIMCTL_CHILD_OS_ACTIVITY_DT_MODE': 'YES'})
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        raise LaunchCommandFailed('Native launch command failed: ' + str(error)) from error
    match = re.search(r': (\d+)\s*$', launched)
    if not match:
        raise RuntimeError('simctl launch did not return a PID')
    pid = int(match[1])
    try:
        identity = command(['ps', '-p', pid, '-o', 'command='])
    except RuntimeError as error:
        try:
            os.kill(pid, 0)
        except ProcessLookupError as gone:
            raise LaunchCrashed('Launched process exited before ps identity proof: ' + str(error), pid) from gone
        raise RuntimeError('ps identity lookup failed: ' + str(error)) from error
    prefix = str((device_root() / udid / 'data').resolve()) + '/'
    if not identity.startswith(prefix) or not re.search(r'\.app/' + re.escape(executable) + r'(?:\s|$)', identity):
        raise RuntimeError('Host PID identity not verified against owned simulator executable')
    deadline = time.monotonic() + duration
    delay = 0.02
    while True:
        try:
            os.kill(pid, 0)
        except ProcessLookupError as error:
            raise LaunchCrashed('Launched process exited during supervision', pid) from error
        if time.monotonic() >= deadline:
            return dict(status='launch_alive', pid=pid, stable_seconds=duration,
                        scope='process alive only; not UI readiness or functional tests')
        time.sleep(min(delay, max(0, deadline - time.monotonic())))
        delay = min(delay * 2, 0.25)


def owned_device(udid, name):
    data = json.loads(command(['xcrun', 'simctl', 'list', '-j', 'devices']))
    return any(d['udid'] == udid and d['name'] == name
               for devices in data['devices'].values() for d in devices)


def cleanup(record, delete):
    udid = record.get('created_udid')
    if not udid or not owned_device(udid, record['created_name']):
        raise RuntimeError('Cleanup refused: exact created UDID/name ledger does not match')
    devices = json.loads(command(['xcrun', 'simctl', 'list', '-j', 'devices']))
    device = next(d for group in devices['devices'].values() for d in group if d['udid'] == udid)
    if device['state'] != 'Shutdown':
        command(['xcrun', 'simctl', 'shutdown', udid])
    if delete:
        command(['xcrun', 'simctl', 'delete', udid])
    record['cleanup'] = 'deleted' if delete else 'shutdown_retained'


def run_pair(args, pair, record, folder, manifest):
    record['status'] = 'running'
    record['started_at'] = datetime.now(timezone.utc).isoformat()
    started = time.time()
    started_monotonic = time.monotonic()
    stop_marker = getattr(args, 'stop_marker', folder.parent / 'matrix-stop.json')
    try:
        check_stop(stop_marker)
        external_path(args.app, '--app')
        external_path(folder, '--output')
        external_path(device_root(), '--device-set')
        disk_preflight(folder)
        folder.mkdir()
        if digest(args.app) != manifest['app_sha256']:
            raise RuntimeError('Frozen app changed during matrix')
        if args.probe == 'xctest':
            verify_test_inputs(args, manifest)
        if DEVICE_SET is not None:
            DEVICE_SET.mkdir(parents=True, exist_ok=True)
        udid = command(['xcrun', 'simctl', 'create', record['created_name'],
                        pair['device_type'], pair['runtime']]).strip()
        if not re.fullmatch(r'[0-9A-Fa-f-]{36}', udid):
            raise RuntimeError('Invalid created simulator UDID')
        record['created_udid'] = udid
        args.persist()
        command(['xcrun', 'simctl', 'boot', udid], timeout=args.boot_timeout,
                log=folder / 'boot.log')
        command(['xcrun', 'simctl', 'bootstatus', udid, '-b'], timeout=args.boot_timeout,
                log=folder / 'bootstatus.log')
        command(['xcrun', 'simctl', 'install', udid, args.app], timeout=180,
                log=folder / 'install.log')
        if args.probe == 'launch':
            record.update(launch_probe(udid, manifest['bundle_id'], manifest['executable'],
                                       args.stable_seconds, folder))
        else:
            record['test_batches'] = []
            for batch in manifest['test_batches']:
                check_stop(stop_marker)
                verify_test_inputs(args, manifest)
                result = folder / (batch['name'] + '.xcresult')
                evidence = dict(batch=batch, status='running')
                record['test_batches'].append(evidence)
                args.persist()
                arguments = ['xcodebuild', 'test-without-building', '-xctestrun', args.xctestrun,
                             '-destination', f'platform=iOS Simulator,id={udid}',
                             '-parallel-testing-enabled', 'NO',
                             '-only-testing:' + batch['selector'],
                             '-resultBundlePath', result]
                if batch['skip']:
                    skips = batch['skip'] if isinstance(batch['skip'], (list, tuple)) else [batch['skip']]
                    arguments.extend('-skip-testing:' + skip for skip in skips)
                test_failed = False
                category = (large_text_category(udid, evidence, folder)
                            if batch['selector'].endswith('/' + LARGE_TEXT) else nullcontext())
                with category:
                    args.persist()
                    try:
                        command(arguments, timeout=args.test_timeout,
                                log=folder / (batch['name'] + '-xcodebuild.log'))
                    except RuntimeError:
                        test_failed = True
                summary = json.loads(command(['xcrun', 'xcresulttool', 'get', 'test-results',
                                              'summary', '--path', result], timeout=60,
                                             log=folder / (batch['name'] + '-summary.json')))
                evidence['summary'] = summary
                evidence['status'] = ('tests_passed' if not test_failed and tests_pass(summary, batch['expected'])
                                      else 'tests_failed' if summary.get('failedTests', 0) > 0
                                      else 'infrastructure_blocked')
                args.persist()
                if evidence['status'] != 'tests_passed':
                    record.update(status=evidence['status'], error='First unexpected XCTest result: ' + batch['selector'])
                    latch_stop(stop_marker, manifest, record, batch['selector'])
                    break
            statuses = [item['status'] for item in record['test_batches']]
            record['status'] = ('tests_failed' if 'tests_failed' in statuses
                                 else 'tests_passed' if len(statuses) == len(manifest['test_batches'])
                                 and all(s == 'tests_passed' for s in statuses)
                                else 'infrastructure_blocked')
            record['expected_test_count'] = sum(b['expected'] for b in manifest['test_batches'])
            record['passed_test_count'] = sum(b['summary'].get('passedTests', 0) for b in record['test_batches'])
    except LaunchCrashed as error:
        record.update(status='unknown_crash', error=str(error), pid=error.pid)
    except LaunchCommandFailed as error:
        record.update(status='infrastructure_blocked', error=str(error), launch_command_failed=True)
    except QueueStopped as error:
        record.update(status='queue_stopped', error=str(error))
    except CapabilityBlocked as error:
        record.update(status='capability_blocked', error=str(error))
        if record.get('test_batches'):
            record['test_batches'][-1].update(status='capability_blocked', error=str(error))
    except DiskSpaceBlocked as error:
        record.update(status='disk_space_blocked', error=str(error))
    except KeyboardInterrupt:
        record.update(status='interrupted', error='Interrupted by signal')
        raise
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        record.update(status='disk_space_blocked' if isinstance(error, OSError) and error.errno == errno.ENOSPC
                      else 'infrastructure_blocked', error=str(error))
        try:
            disk_preflight(folder)
        except DiskSpaceBlocked as space_error:
            record.update(status='disk_space_blocked', error=str(space_error))
    finally:
        if record.get('created_udid'):
            try:
                if record['status'] in ('interrupted', 'disk_space_blocked', 'queue_stopped') or record.get('launch_command_failed'):
                    raise RuntimeError('Crash evidence collection skipped after interruption or low disk space')
                crash = 'lifecycle_trap' if LIFECYCLE in launch_console(folder) else 'infrastructure_blocked'
                deadline = time.monotonic() + (10 if record['status'] not in ('tests_passed', 'launch_alive') else 0)
                delay = 0.05
                while crash != 'lifecycle_trap' and record['status'] != 'launch_alive':
                    crash = crash_evidence(record['created_udid'], manifest['bundle_id'],
                                           manifest['executable'], started, folder)
                    if crash != 'infrastructure_blocked' or time.monotonic() >= deadline:
                        break
                    time.sleep(min(delay, max(0, deadline - time.monotonic())))
                    delay = min(delay * 2, 0.5)
                if crash != 'infrastructure_blocked':
                    record['status'] = crash
                if record['status'] not in ('tests_passed', 'launch_alive'):
                    try:
                        command(['xcrun', 'simctl', 'io', record['created_udid'], 'screenshot',
                                 folder / 'failure.png'], timeout=30)
                    except (RuntimeError, subprocess.TimeoutExpired):
                        pass
            except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
                record['evidence_error'] = str(error)
                if record['status'] not in ('interrupted', 'disk_space_blocked', 'queue_stopped', 'unknown_crash'):
                    record['status'] = 'infrastructure_blocked'
            try:
                cleanup(record, args.delete_created)
            except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
                record['cleanup_error'] = str(error)
                if record['status'] not in ('interrupted', 'disk_space_blocked', 'queue_stopped'):
                    record['status'] = 'infrastructure_blocked'
        if record['status'] == 'running':
            record['status'] = 'interrupted'
        record['finished_at'] = datetime.now(timezone.utc).isoformat()
        record['duration_seconds'] = time.monotonic() - started_monotonic
        if unexpected(manifest, record['status']) and record['status'] != 'queue_stopped':
            latch_stop(stop_marker, manifest, record, (record.get('test_batches') or [{}])[-1].get('batch', {}).get('selector', 'pair'))
        args.persist()


def main():
    global DEVICE_SET, CHILD_TMPDIR
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--list', action='store_true', help='read-only dynamic inventory; writes nothing')
    parser.add_argument('--progress', type=Path,
                        help='read-only ETA from an absolute matrix.json; no inventory or simulator commands')
    parser.add_argument('--validate-only', action='store_true',
                        help='validate frozen external inputs and print manifest; no inventory, writes or execution')
    parser.add_argument('--app', type=Path, help='absolute frozen simulator .app')
    parser.add_argument('--output', type=Path, help='explicit new evidence directory, or matching --resume directory')
    parser.add_argument('--phase', choices=('baseline', 'fixed'))
    parser.add_argument('--config', choices=('Debug', 'Release'))
    parser.add_argument('--probe', choices=('xctest', 'launch'), default='xctest')
    parser.add_argument('--device-set', type=Path,
                        help='required mounted /Volumes/data device set; explicit per-command simctl/Xcode setting')
    parser.add_argument('--xctestrun', type=Path)
    parser.add_argument('--build-manifest', type=Path,
                        help='build provenance JSON with configuration and app_sha256; required for XCTest if app lacks configuration')
    parser.add_argument('--selector', default='LinkaStartupUITests/MatrixSmokeTests',
                        help='approved MatrixSmokeTests target/class selector only')
    parser.add_argument('--only-runtime', help='exact runtime identifier; pilot scope')
    parser.add_argument('--only-device-type', help='exact device type identifier; pilot scope')
    parser.add_argument('--limit', type=int, help='positive pair count; pilot scope')
    parser.add_argument('--stable-seconds', type=float, default=3)
    parser.add_argument('--boot-timeout', type=int, default=240)
    parser.add_argument('--test-timeout', type=int, default=600)
    parser.add_argument('--resume', action='store_true', help='never reruns completed or interrupted records')
    parser.add_argument('--delete-created', action='store_true', help='explicitly delete only exact owned ledger UDIDs')
    args = parser.parse_args()
    if args.progress:
        if not args.progress.is_absolute():
            parser.error('--progress must be an absolute matrix.json path')
        print(json.dumps(progress(json.loads(args.progress.read_text()), datetime.now(timezone.utc)), indent=2))
        return 0
    if args.list:
        DEVICE_SET = None
        pairs = inventory()
        print(json.dumps(dict(pair_count=len(pairs), pairs=pairs), indent=2))
        return 0
    if not all((args.app, args.output, args.phase, args.config, args.device_set)):
        parser.error('--app, --output, --phase, --config and --device-set are required for a run')
    DEVICE_SET = external_path(args.device_set, '--device-set')
    if DEVICE_SET.exists() and not DEVICE_SET.is_dir():
        parser.error('--device-set must name a directory')
    for label, path in (('--app', args.app), ('--output', args.output),
                        ('--xctestrun', args.xctestrun), ('--build-manifest', args.build_manifest)):
        if path is not None:
            external_path(path, label)
    args.stop_marker = args.output.parent / 'matrix-stop.json'
    check_stop(args.stop_marker)
    if not args.app.is_absolute() or args.app.suffix != '.app' or not args.app.is_dir():
        parser.error('--app must be an absolute existing .app directory')
    if not args.output.is_absolute():
        parser.error('--output must be absolute')
    if args.output.resolve() == args.app.resolve() or args.app.resolve() in args.output.resolve().parents:
        parser.error('--output cannot be inside frozen app')
    if DEVICE_SET is not None and (DEVICE_SET == args.app.resolve() or args.app.resolve() in DEVICE_SET.parents):
        parser.error('--device-set cannot be inside frozen app')
    if args.probe == 'xctest' and (not args.xctestrun or not args.xctestrun.is_absolute()
                                   or not args.xctestrun.is_file()):
        parser.error('xctest probe requires an absolute existing --xctestrun')
    batches = test_batches(args.selector)
    if (args.limit is not None and args.limit < 1) or not (0 < args.stable_seconds <= 300):
        parser.error('positive --limit and 0 < --stable-seconds <= 300 required')
    if not (0 < args.boot_timeout <= 1800 and 0 < args.test_timeout <= 7200):
        parser.error('timeouts must be bounded: boot <= 1800, test <= 7200 seconds')
    info = plistlib.loads((args.app / 'Info.plist').read_bytes())
    bundle, executable = info['CFBundleIdentifier'], info['CFBundleExecutable']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', bundle) or not re.fullmatch(r'[A-Za-z0-9_. -]+', executable):
        parser.error('Invalid bundle identifier/executable')
    if not (args.app / executable).is_file() or 'iPhoneSimulator' not in info.get('CFBundleSupportedPlatforms', []):
        parser.error('App is not a simulator product with an executable')
    app_sha = digest(args.app)
    provenance = configuration_provenance(info, args.config, app_sha, args.build_manifest,
                                          required=args.probe == 'xctest')
    artifacts = test_artifacts(args.xctestrun, bundle, app_sha, require_external=True) if args.probe == 'xctest' else {}
    for artifact in artifacts:
        external_path(Path(artifact), 'test artifact')
    # Validation never queries CoreSimulator; actual runs freeze its available inventory.
    pairs = []
    selected = [p for p in pairs if (not args.only_runtime or p['runtime'] == args.only_runtime)
                and (not args.only_device_type or p['device_type'] == args.only_device_type)]
    if args.limit:
        selected = selected[:args.limit]
    manifest = dict(phase=args.phase, configuration=args.config, app=str(args.app.resolve()),
                    device_set=str(device_root().resolve()), min_free_bytes=MIN_FREE_BYTES,
                    app_sha256=app_sha, bundle_id=bundle, executable=executable,
                    configuration_provenance=provenance, test_artifact_hashes=artifacts,
                    test_batches=batches if args.probe == 'xctest' else [],
                    native_content_size=LARGE_CATEGORY if args.probe == 'xctest'
                    and any(b['selector'].endswith('/' + LARGE_TEXT) for b in batches) else None,
                    probe=args.probe, selector=args.selector, stable_seconds=args.stable_seconds,
                    scope='pilot' if args.only_runtime or args.only_device_type or args.limit else 'full',
                    inventory=pairs, selected=selected,
                    xctestrun=str(args.xctestrun.resolve()) if args.xctestrun else None,
                    xctestrun_sha256=digest(args.xctestrun) if args.xctestrun else None)
    manifest['execution_policy'] = 'external-only, fail-fast, one XCTest method per invocation'
    manifest['xctest_custom_set_proof'] = 'requires future real pilot; no execution claimed'
    if args.validate_only:
        print(json.dumps(dict(validated=True, pair_count=None, manifest=manifest), indent=2))
        return 0
    # One heavy matrix per workspace; separate UDIDs still share host disk and CPU.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with (args.output.parent / '.simulator-matrix.lock').open('a') as run_lock:
        try:
            fcntl.flock(run_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error('Another matrix is running in this evidence workspace')
        try:
            return run_matrix(args, manifest)
        except KeyboardInterrupt:
            latch_stop(args.stop_marker, manifest, dict(status='interrupted', error='Interrupted during run setup'), 'setup')
            raise
        except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
            latch_stop(args.stop_marker, manifest, dict(status='infrastructure_blocked', error=str(error)), 'setup')
            raise
        finally:
            CHILD_TMPDIR = None


def run_matrix(args, manifest):
    global CHILD_TMPDIR
    check_stop(args.stop_marker)
    state_path = args.output / 'matrix.json'
    if not args.resume:
        args.output.mkdir(parents=True, exist_ok=False)
    CHILD_TMPDIR = external_path(child_tmpdir(args.output), 'child TMPDIR')
    CHILD_TMPDIR.mkdir(parents=True, exist_ok=True)
    DEVICE_SET.mkdir(parents=True, exist_ok=True)
    pairs = inventory()
    selected = [p for p in pairs if (not args.only_runtime or p['runtime'] == args.only_runtime)
                and (not args.only_device_type or p['device_type'] == args.only_device_type)]
    if args.limit:
        selected = selected[:args.limit]
    if not selected:
        raise ValueError('Pilot selection has no pairs')
    manifest.update(inventory=pairs, selected=selected)
    if args.resume:
        state = json.loads(state_path.read_text())
        validate_resume(state, manifest)
        for record in state['records']:
            if record['status'] in ('running', 'pending'):
                record['status'] = 'interrupted'
                if record.get('created_udid'):
                    try:
                        cleanup(record, args.delete_created)
                    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
                        record['cleanup_error'] = str(error)
    else:
        state = dict(manifest=manifest, run_id=uuid.uuid4().hex, records=[],
                     started_at=datetime.now(timezone.utc).isoformat())

    def persist():
        state['progress'] = progress(state, datetime.now(timezone.utc))
        atomic_json(state_path, state)

    args.persist = persist
    for record in state['records']:
        if unexpected(manifest, record['status']):
            latch_stop(args.stop_marker, manifest, record, 'resume')
            state['blocked_reason'] = 'Resume contains an unexpected/interrupted result; automatic continuation blocked'
            break
    args.persist()
    for index, pair in enumerate(selected):
        if index < len(state['records']):
            continue
        try:
            check_stop(args.stop_marker)
        except QueueStopped as error:
            state['blocked_reason'] = str(error)
            args.persist()
            break
        record = dict(pair=pair, status='pending',
                      created_name=f'linka-matrix-{state["run_id"]}-{index}')
        state['records'].append(record)
        args.persist()
        try:
            run_pair(args, pair, record, args.output / f'{index:04d}', manifest)
        except KeyboardInterrupt:
            record['status'] = 'interrupted'
            state['interrupted'] = True
            state['blocked_reason'] = 'Interrupted; queue stop marker retained'
            args.persist()
            break
        stop = unexpected(manifest, record['status'])
        if stop:
            state['blocked_reason'] = record.get('error', record['status'])
            args.persist()
        report = state['progress']
        elapsed = report['elapsed_seconds']
        eta = report['eta_seconds']
        elapsed_text = f'{elapsed / 60:.1f}m' if elapsed is not None else 'unknown'
        eta_text = f'{eta / 60:.1f}m' if eta is not None else 'unknown'
        print(f'[{report["processed"]}/{report["total"]}] {pair["runtime_name"]} {pair["device_name"]}: '
              f'{record["status"]}; elapsed {elapsed_text}; ETA {eta_text}', flush=True)
        if stop:
            break
    passed = (args.phase == 'fixed' and args.probe == 'xctest'
              and len(state['records']) == len(selected)
              and all(r['status'] == 'tests_passed' for r in state['records']))
    state['outcome'] = ('tests_passed' if passed else 'evidence_only_or_incomplete')
    state['lifecycle_traps'] = sum(r['status'] == 'lifecycle_trap' for r in state['records'])
    state['full_matrix_bug_reproduced'] = baseline_reproduced(manifest, state['records'])
    args.persist()
    return 130 if state.get('interrupted') else 0 if passed else 1


if __name__ == '__main__':
    def interrupt(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (ValueError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
