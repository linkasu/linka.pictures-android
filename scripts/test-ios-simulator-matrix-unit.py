#!/usr/bin/env python3
"""Offline unit checks for the owned-device matrix runner; no simulator commands."""
import importlib.util
import io
from datetime import datetime, timezone
import json
from pathlib import Path
import plistlib
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('matrix', Path(__file__).with_name('test-ios-simulator-matrix.py'))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)
EXTERNAL_PATH = matrix.external_path


class MatrixTests(unittest.TestCase):
    def test_original_console_signature_survives_early_ps_failure_without_log_query(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(app=root / 'Original.app', probe='launch', stable_seconds=3,
                                   boot_timeout=30, delete_created=False, persist=lambda: None)
            manifest = dict(phase='baseline', probe='launch', app_sha256='sha',
                            bundle_id='su.linka.pictures', executable='App')
            record = dict(created_name='own-name', status='pending')
            calls = []

            def command(arguments, **kwargs):
                calls.append((arguments, kwargs))
                if arguments[1:3] == ['simctl', 'create']:
                    return '12345678-1234-1234-1234-123456789ABC'
                if 'launch' in arguments:
                    (root / 'evidence/stderr.log').write_text(
                        "*** Terminating app: '_UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption' "
                        'UIScene life cycle is required')
                    return 'su.linka.pictures: 20776\n'
                if arguments[0] == 'ps':
                    raise RuntimeError('ps: process exited')
                return ''

            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'command', side_effect=command), \
                    patch.object(matrix.os, 'kill', side_effect=ProcessLookupError), \
                    patch.object(matrix, 'cleanup'):
                matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record,
                                root / 'evidence', manifest)
            self.assertEqual(record['status'], 'lifecycle_trap')
            self.assertEqual(record['pid'], 20776)
            launches = [(call, kwargs) for call, kwargs in calls if 'launch' in call]
            self.assertEqual(len(launches), 1)
            self.assertIn('--stdout=' + str(root / 'evidence/stdout.log'), launches[0][0])
            self.assertIn('--stderr=' + str(root / 'evidence/stderr.log'), launches[0][0])
            self.assertEqual(launches[0][1]['env_overrides'], {'SIMCTL_CHILD_OS_ACTIVITY_DT_MODE': 'YES'})
            self.assertFalse(any('spawn' in call for call, _ in calls))
            self.assertFalse((root / 'matrix-stop.json').exists())

    def test_launch_environment_override_is_allowlisted_and_process_local(self):
        process = SimpleNamespace(communicate=lambda **kwargs: (b'ok', None), returncode=0)
        before = dict(matrix.os.environ)
        with patch.object(matrix.subprocess, 'Popen') as popen:
            popen.return_value.__enter__.return_value = process
            matrix.command(['xcrun', 'simctl', 'launch', 'OWN', 'su.linka.pictures'],
                           env_overrides={'SIMCTL_CHILD_OS_ACTIVITY_DT_MODE': 'YES'})
            self.assertEqual(popen.call_args.kwargs['env']['SIMCTL_CHILD_OS_ACTIVITY_DT_MODE'], 'YES')
            with self.assertRaises(ValueError):
                matrix.command(['ps', '-p', '123'], env_overrides={'SIMCTL_CHILD_OS_ACTIVITY_DT_MODE': 'YES'})
            with self.assertRaises(ValueError):
                matrix.command(['xcrun', 'simctl', 'launch', 'OWN', 'su.linka.pictures'],
                               env_overrides={'SECRET': 'not-allowed'})
        self.assertEqual(dict(matrix.os.environ), before)

    def test_alive_launch_checks_console_only_without_slow_crash_query(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(app=root / 'Original.app', probe='launch', stable_seconds=3,
                                   boot_timeout=30, delete_created=False, persist=lambda: None)
            manifest = dict(phase='baseline', probe='launch', app_sha256='sha',
                            bundle_id='su.linka.pictures', executable='App')
            record = dict(created_name='own-name', status='pending')
            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'command', return_value='12345678-1234-1234-1234-123456789ABC'), \
                    patch.object(matrix, 'launch_probe', return_value=dict(status='launch_alive', pid=123)), \
                    patch.object(matrix, 'crash_evidence') as crash, patch.object(matrix, 'cleanup'):
                matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record, root / 'evidence', manifest)
                crash.assert_not_called()
            self.assertEqual(record['status'], 'launch_alive')

    def test_native_launch_command_error_is_infrastructure_not_crash(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(matrix, 'command', side_effect=RuntimeError('native launch failed')) as command:
                with self.assertRaises(matrix.LaunchCommandFailed):
                    matrix.launch_probe('OWN', 'su.linka.pictures', 'App', 3, Path(directory))
                self.assertEqual(command.call_count, 1)

    def test_launched_process_disappearing_during_poll_is_confirmed_crash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            identity = str(root / 'OWN/data/Containers/Bundle/Application/A/App.app/App')
            with patch.object(matrix, 'DEVICE_SET', root), \
                    patch.object(matrix, 'command', side_effect=['su.linka.pictures: 123\n', identity]) as command, \
                    patch.object(matrix.os, 'kill', side_effect=ProcessLookupError), \
                    patch.object(matrix.time, 'monotonic', return_value=0):
                with self.assertRaises(matrix.LaunchCrashed) as crashed:
                    matrix.launch_probe('OWN', 'su.linka.pictures', 'App', 3, root)
                self.assertEqual(crashed.exception.pid, 123)
                self.assertEqual(command.call_count, 2)

    def test_process_death_without_signature_is_unknown_crash_not_infrastructure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(app=root / 'Original.app', probe='launch', stable_seconds=3,
                                   boot_timeout=30, delete_created=False, persist=lambda: None)
            manifest = dict(phase='baseline', probe='launch', app_sha256='sha',
                            bundle_id='su.linka.pictures', executable='App')
            record = dict(created_name='own-name', status='pending')
            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'command', return_value='12345678-1234-1234-1234-123456789ABC'), \
                    patch.object(matrix, 'launch_probe', side_effect=matrix.LaunchCrashed('process exited', 123)), \
                    patch.object(matrix, 'crash_evidence', return_value='infrastructure_blocked'), \
                    patch.object(matrix.time, 'monotonic', side_effect=[0, 0, 11, 12]), \
                    patch.object(matrix, 'cleanup'):
                matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record, root / 'evidence', manifest)
            self.assertEqual(record['status'], 'unknown_crash')
            self.assertEqual(record['pid'], 123)
            self.assertTrue((root / 'matrix-stop.json').exists())

    def test_console_reader_bounds_large_logs_and_keeps_startup_and_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for name in ('stdout.log', 'stderr.log'):
                (folder / name).write_bytes(b'start\n' + b'x' * (3 * 1024 ** 2) + b'\ntail')
            evidence = matrix.launch_console(folder)
            self.assertLessEqual(len(evidence.encode()), 2 * 1024 ** 2 + 1)
            self.assertEqual(evidence.count('start\n'), 2)
            self.assertEqual(evidence.count('\ntail'), 2)

    def test_progress_uses_terminal_durations_and_excludes_running_interval(self):
        state = dict(manifest=dict(selected=list(range(8)), phase='baseline', probe='launch'), records=[
            dict(status='launch_alive', duration_seconds=10),
            dict(status='lifecycle_trap', duration_seconds=20),
            dict(status='launch_alive', duration_seconds=30),
            dict(status='running', duration_seconds=9999, pair=dict(device_name='iPad'))])
        report = matrix.progress(state, datetime(2026, 11, 12, tzinfo=timezone.utc))
        self.assertEqual((report['processed'], report['total'], report['remaining']), (3, 8, 5))
        self.assertEqual(report['median_seconds'], 20)
        self.assertEqual(report['mean_seconds'], 20)
        self.assertEqual(report['eta_seconds'], 100)
        self.assertEqual(report['eta_range_seconds'], [75, 125])
        self.assertEqual(report['activepair'], dict(device_name='iPad'))
        self.assertEqual(report['estimated_finishISO'], '2026-11-12T00:01:40+00:00')
        state['blocked_reason'] = 'First unexpected test'
        self.assertIsNone(matrix.progress(state, datetime(2026, 11, 12, tzinfo=timezone.utc))['eta_seconds'])

    def test_progress_legacy_adjacent_starts_not_active_age(self):
        state = dict(manifest=dict(selected=list(range(5))), records=[
            dict(status='launch_alive', started_at='2026-11-12T00:00:00+00:00'),
            dict(status='lifecycle_trap', started_at='2026-11-12T00:00:10+00:00'),
            dict(status='launch_alive', started_at='2026-11-12T00:00:30+00:00'),
            dict(status='running', started_at='2026-11-12T00:01:00+00:00')])
        report = matrix.progress(state, datetime(2026, 11, 12, 1, tzinfo=timezone.utc))
        self.assertEqual(report['sample_count'], 3)
        self.assertEqual(report['median_seconds'], 20)
        self.assertEqual(report['eta_seconds'], 40)
        self.assertEqual(report['elapsed_seconds'], 3600)
        self.assertIn('adjacent_started_at', report['estimatebasis'])

    def test_progress_invalid_or_too_few_durations_do_not_invent_eta(self):
        state = dict(manifest=dict(selected=list(range(9))), records=[
            dict(status='launch_alive', duration_seconds=value) for value in
            [-1, float('nan'), float('inf'), '20', True, 0, 10, 20]])
        report = matrix.progress(state, datetime(2026, 11, 12, tzinfo=timezone.utc))
        self.assertEqual(report['sample_count'], 2)
        self.assertIsNone(report['eta_seconds'])
        self.assertIsNone(report['estimated_finishISO'])
        self.assertIn('insufficient', report['estimatebasis'])
        legacy = dict(manifest=dict(selected=[1, 2]), records=[
            dict(status='launch_alive', started_at='2026-11-12T00:01:00+00:00'),
            dict(status='running', started_at='2026-11-12T00:00:00+00:00')])
        self.assertEqual(matrix.progress(legacy, datetime(2026, 11, 12, tzinfo=timezone.utc))['sample_count'], 0)

    def test_progress_recent_twenty_and_completed_zero_eta(self):
        state = dict(manifest=dict(selected=list(range(24))), records=[
            dict(status='tests_passed', duration_seconds=1000 if i < 4 else 10) for i in range(24)])
        report = matrix.progress(state, datetime(2026, 11, 12, tzinfo=timezone.utc))
        self.assertEqual(report['sample_count'], 20)
        self.assertEqual(report['median_seconds'], 10)
        self.assertEqual(report['eta_seconds'], 0)
        self.assertEqual(report['remaining'], 0)
        state['records'][-1]['status'] = 'disk_space_blocked'
        report = matrix.progress(state, datetime(2026, 11, 12, tzinfo=timezone.utc))
        self.assertEqual(report['blocked'], 1)
        self.assertEqual(report['status_counts']['disk_space_blocked'], 1)
        state['manifest']['selected'].append(25)
        report = matrix.progress(state, datetime(2026, 11, 12, tzinfo=timezone.utc))
        self.assertIsNone(report['eta_seconds'])
        self.assertEqual(report['blocked_remaining'], 1)

    def test_progress_cli_reads_only_and_never_calls_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'matrix.json'
            path.write_text(json.dumps(dict(manifest=dict(selected=[]), records=[])))
            before = path.read_bytes()
            with patch.object(matrix.sys, 'argv', ['matrix', '--progress', str(path)]), \
                    patch.object(matrix, 'inventory') as inventory, \
                    patch.object(matrix, 'command') as command, \
                    patch.object(matrix, 'atomic_json') as write, \
                    patch.object(matrix.sys, 'stdout', new_callable=io.StringIO) as stdout:
                self.assertEqual(matrix.main(), 0)
                self.assertEqual(json.loads(stdout.getvalue())['total'], 0)
                inventory.assert_not_called()
                command.assert_not_called()
                write.assert_not_called()
            self.assertEqual(path.read_bytes(), before)

    def setUp(self):
        self.disk_usage = patch.object(matrix.shutil, 'disk_usage', return_value=SimpleNamespace(free=100 * 1024 ** 3))
        self.disk_usage.start()
        self.addCleanup(self.disk_usage.stop)
        self.storage = patch.object(matrix, 'external_path', side_effect=lambda path, label: path.resolve())
        self.storage.start()
        self.addCleanup(self.storage.stop)
        self.addCleanup(setattr, matrix, 'CHILD_TMPDIR', None)
        self.addCleanup(setattr, matrix, 'DEVICE_SET', None)

    def test_custom_device_set_applies_to_every_simctl_command_only(self):
        process = SimpleNamespace(communicate=lambda **kwargs: (b'ok', None), returncode=0)
        with patch.object(matrix, 'DEVICE_SET', Path('/Volumes/data/matrix-devices'), create=True), \
                patch.object(matrix.subprocess, 'Popen') as popen:
            popen.return_value.__enter__.return_value = process
            for tail in [('create', 'name', 'phone', 'runtime'), ('list', '-j', 'devices'),
                         ('shutdown', 'OWN'), ('spawn', 'OWN', 'log', 'show')]:
                matrix.command(['xcrun', 'simctl', *tail])
                self.assertEqual(popen.call_args.args[0], ['xcrun', 'simctl', '--set',
                                                          '/Volumes/data/matrix-devices', *tail])
            matrix.command(['ps', '-p', '123'])
            self.assertEqual(popen.call_args.args[0], ['ps', '-p', '123'])

    def test_xcode_custom_set_and_child_tmpdir_are_scoped_not_global(self):
        process = SimpleNamespace(communicate=lambda **kwargs: (b'ok', None), returncode=0)
        before = dict(matrix.os.environ)
        with patch.object(matrix, 'DEVICE_SET', Path('/Volumes/data/devices')), \
                patch.object(matrix, 'CHILD_TMPDIR', Path('/Volumes/data/evidence/tmp')), \
                patch.object(matrix.subprocess, 'Popen') as popen:
            popen.return_value.__enter__.return_value = process
            matrix.command(['xcodebuild', 'test-without-building'])
            self.assertEqual(popen.call_args.args[0], ['xcodebuild',
                             '-DVTSimulatorSetLocation=/Volumes/data/devices', 'test-without-building'])
            self.assertEqual(popen.call_args.kwargs['env']['TMPDIR'], '/Volumes/data/evidence/tmp')
        self.assertEqual(dict(matrix.os.environ), before)

    def test_child_tmpdir_uses_short_external_root_not_evidence_path(self):
        evidence = Path('/Volumes/data/project/build/' + 'long-evidence-path/' * 8)
        path = matrix.child_tmpdir(evidence)
        self.assertTrue(path.is_relative_to(Path('/Volumes/data/.linka-tmp')))
        self.assertLessEqual(len(matrix.os.fsencode(path)), 48)
        self.assertEqual(path, matrix.child_tmpdir(evidence))
        self.assertNotEqual(path, matrix.child_tmpdir(evidence / 'other-run'))

    def test_external_storage_guard_rejects_unmounted_volume_and_internal_paths(self):
        with patch.object(matrix.Path, 'is_mount', return_value=False):
            with self.assertRaisesRegex(ValueError, 'Mounted external'):
                EXTERNAL_PATH(Path('/Volumes/data/frozen.app'), 'app')
        with patch.object(matrix.Path, 'is_mount', return_value=True), \
                patch.object(matrix.Path, 'stat', autospec=True,
                             side_effect=lambda path, **kwargs: SimpleNamespace(st_dev=2 if str(path) == '/Volumes/data' else 1)):
            with self.assertRaisesRegex(ValueError, 'must resolve beneath'):
                EXTERNAL_PATH(Path('/tmp/frozen.app'), 'app')
        def resolved(path, **kwargs):
            return Path('/private/tmp/internal') if str(path) == '/Volumes/data/alias' else path
        with patch.object(matrix.Path, 'is_mount', return_value=True), \
                patch.object(matrix.Path, 'resolve', autospec=True, side_effect=resolved), \
                patch.object(matrix.Path, 'stat', autospec=True,
                             side_effect=lambda path, **kwargs: SimpleNamespace(st_dev=2 if str(path) == '/Volumes/data' else 1)):
            with self.assertRaisesRegex(ValueError, 'must resolve beneath'):
                EXTERNAL_PATH(Path('/Volumes/data/alias'), 'app')

    def test_validate_only_xctest_checks_frozen_inputs_without_writes_or_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            app = root / 'Frozen.app'
            app.mkdir()
            (app / 'App').write_bytes(b'app')
            (app / 'Info.plist').write_bytes(plistlib.dumps(dict(CFBundleIdentifier='su.linka.pictures',
                CFBundleExecutable='App', CFBundleSupportedPlatforms=['iPhoneSimulator'], LinkaBuildConfiguration='Debug')))
            host = root / 'Runner.app'
            (host / 'PlugIns/Smoke.xctest').mkdir(parents=True)
            (host / 'PlugIns/Smoke.xctest/Smoke').write_bytes(b'test')
            path = root / 'Smoke.xctestrun'
            path.write_bytes(plistlib.dumps(dict(LinkaStartupUITests=dict(TestHostPath='__TESTROOT__/Runner.app',
                TestBundlePath='__TESTHOST__/PlugIns/Smoke.xctest',
                TestingEnvironmentVariables=dict(LINKA_UI_PRODUCT_BUNDLE_ID='su.linka.pictures')))))
            argv = ['matrix', '--app', str(app), '--output', str(root / 'output'), '--phase', 'fixed',
                    '--config', 'Debug', '--xctestrun', str(path), '--device-set', str(root / 'devices'), '--validate-only']
            with patch.object(matrix.sys, 'argv', argv), patch.object(matrix, 'inventory') as inventory, \
                    patch.object(matrix, 'command') as command, patch.object(matrix.Path, 'mkdir') as mkdir, \
                    patch.object(matrix, 'atomic_json') as write, \
                    patch.object(matrix.sys, 'stdout', new_callable=io.StringIO) as stdout:
                self.assertEqual(matrix.main(), 0)
                result = json.loads(stdout.getvalue())
                self.assertTrue(result['validated'])
                self.assertIsNone(result['pair_count'])
                self.assertEqual(len(result['manifest']['test_batches']), 8)
                inventory.assert_not_called()
                command.assert_not_called()
                mkdir.assert_not_called()
                write.assert_not_called()
            self.assertFalse((root / 'devices').exists())
            self.assertFalse((root / 'output').exists())

    def test_setup_exception_closes_lock_and_prepares_external_tmp_before_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            app = root / 'Frozen.app'
            app.mkdir()
            (app / 'App').write_bytes(b'app')
            (app / 'Info.plist').write_bytes(plistlib.dumps(dict(CFBundleIdentifier='su.linka.pictures',
                CFBundleExecutable='App', CFBundleSupportedPlatforms=['iPhoneSimulator'])))
            argv = ['matrix', '--app', str(app), '--output', str(root / 'output'), '--phase', 'baseline',
                    '--config', 'Debug', '--probe', 'launch', '--device-set', str(root / 'devices')]

            def inventory():
                self.assertEqual(matrix.CHILD_TMPDIR, root / 'child-tmp')
                self.assertTrue(matrix.CHILD_TMPDIR.is_dir())
                self.assertTrue((root / 'devices').is_dir())
                raise RuntimeError('inventory failed')

            with patch.object(matrix.sys, 'argv', argv), patch.object(matrix, 'inventory', side_effect=inventory), \
                    patch.object(matrix, 'child_tmpdir', return_value=root / 'child-tmp'), \
                    patch.object(matrix, 'command') as command, patch.object(matrix.fcntl, 'flock') as lock:
                with self.assertRaisesRegex(RuntimeError, 'inventory failed'):
                    matrix.main()
                command.assert_not_called()
                self.assertTrue(lock.call_args.args[0].closed)
            self.assertIsNone(matrix.CHILD_TMPDIR)
            self.assertEqual(json.loads((root / 'matrix-stop.json').read_text())['stage'], 'setup')

    def test_stop_marker_preserves_first_cause_and_blocks_before_create(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / 'matrix-stop.json'
            manifest = dict(phase='fixed', probe='xctest', configuration='Debug')
            first = dict(status='tests_failed', pair=dict(device_name='first'))
            matrix.latch_stop(marker, manifest, first, 'testFirst')
            before = marker.read_bytes()
            matrix.latch_stop(marker, manifest, dict(status='unknown_crash'), 'testSecond')
            self.assertEqual(marker.read_bytes(), before)
            self.assertEqual(json.loads(before)['stage'], 'testFirst')
            args = SimpleNamespace(stop_marker=marker, persist=lambda: None)
            record = dict(status='pending')
            with patch.object(matrix, 'command') as command:
                matrix.run_pair(args, {}, record, root / 'evidence', manifest)
                command.assert_not_called()
            self.assertEqual(record['status'], 'queue_stopped')
            self.assertFalse((root / 'evidence').exists())

    def test_only_baseline_launch_trap_is_expected(self):
        self.assertFalse(matrix.unexpected(dict(phase='baseline', probe='launch'), 'lifecycle_trap'))
        self.assertTrue(matrix.unexpected(dict(phase='fixed', probe='launch'), 'lifecycle_trap'))
        self.assertTrue(matrix.unexpected(dict(phase='baseline', probe='xctest'), 'lifecycle_trap'))
        for status in ['tests_failed', 'unknown_crash', 'capability_blocked', 'disk_space_blocked', 'infrastructure_blocked']:
            self.assertTrue(matrix.unexpected(dict(phase='baseline', probe='launch'), status))

    def test_first_xctest_failure_latches_stop_and_never_starts_second_method(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(app=root / 'App.app', xctestrun=root / 'Smoke.xctestrun', probe='xctest',
                                   boot_timeout=30, test_timeout=60, delete_created=False,
                                   persist=lambda: None, stop_marker=root / 'matrix-stop.json')
            manifest = dict(phase='fixed', probe='xctest', app_sha256='sha', bundle_id='su.linka.pictures',
                            executable='App', test_batches=matrix.test_batches('LinkaStartupUITests/MatrixSmokeTests'))
            record = dict(created_name='owned-name', status='pending')
            calls = []

            def command(arguments, **kwargs):
                calls.append(arguments)
                if arguments[1:3] == ['simctl', 'create']:
                    return '12345678-1234-1234-1234-123456789ABC'
                if 'xcresulttool' in arguments:
                    return json.dumps(dict(passedTests=0, totalTestCount=1, failedTests=1,
                                           skippedTests=0, result='Failed'))
                return ''

            with patch.object(matrix, 'digest', return_value='sha'), patch.object(matrix, 'verify_test_inputs'), \
                    patch.object(matrix, 'command', side_effect=command), \
                    patch.object(matrix, 'crash_evidence', return_value='infrastructure_blocked'), \
                    patch.object(matrix.time, 'monotonic', side_effect=[0, 0, 11, 12]), \
                    patch.object(matrix, 'cleanup'):
                matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record, root / 'evidence', manifest)
            self.assertEqual(len([call for call in calls if call[0] == 'xcodebuild']), 1)
            self.assertEqual(len(record['test_batches']), 1)
            self.assertEqual(record['status'], 'tests_failed')
            self.assertEqual(json.loads(args.stop_marker.read_text())['stage'],
                             'LinkaStartupUITests/MatrixSmokeTests/testColdLaunchAndRelaunch')

    def test_fixed_trap_stops_queue_but_baseline_trap_continues(self):
        for phase, expected_records in [('fixed', 1), ('baseline', 2)]:
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                app = root / 'App.app'
                app.mkdir()
                (app / 'App').write_bytes(b'app')
                (app / 'Info.plist').write_bytes(plistlib.dumps(dict(CFBundleIdentifier='su.linka.pictures',
                    CFBundleExecutable='App', CFBundleSupportedPlatforms=['iPhoneSimulator'])))
                output = root / 'output'
                pair = dict(runtime='runtime', device_type='phone', runtime_name='iOS', device_name='iPhone')
                argv = ['matrix', '--app', str(app), '--output', str(output), '--phase', phase,
                        '--config', 'Debug', '--probe', 'launch', '--device-set', str(root / 'devices')]
                with patch.object(matrix.sys, 'argv', argv), \
                        patch.object(matrix, 'external_path', side_effect=lambda path, label: path.resolve()), \
                        patch.object(matrix, 'inventory', return_value=[pair, dict(pair, device_type='ipad')]), \
                        patch.object(matrix, 'launch_probe', return_value=dict(status='launch_alive')), \
                        patch.object(matrix, 'launch_console', return_value=matrix.LIFECYCLE), \
                        patch.object(matrix, 'crash_evidence', return_value='lifecycle_trap'), \
                        patch.object(matrix, 'cleanup'), \
                        patch.object(matrix, 'command', return_value='12345678-1234-1234-1234-123456789ABC'), \
                        patch('builtins.print'):
                    self.assertEqual(matrix.main(), 1)
                state = json.loads((output / 'matrix.json').read_text())
                self.assertEqual(len(state['records']), expected_records)
                self.assertEqual((root / 'matrix-stop.json').exists(), phase == 'fixed')
                if phase == 'fixed':
                    self.assertIsNone(state['progress']['eta_seconds'])
                self.assertIsNone(matrix.CHILD_TMPDIR)

    def test_preexisting_stop_marker_blocks_entire_next_queue_before_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            marker = root / 'matrix-stop.json'
            matrix.latch_stop(marker, dict(phase='fixed'), dict(status='tests_failed'), 'previous-run')
            before = marker.read_bytes()
            argv = ['matrix', '--app', str(root / 'Missing.app'), '--output', str(root / 'next'),
                    '--phase', 'fixed', '--config', 'Debug', '--device-set', str(root / 'devices')]
            with patch.object(matrix.sys, 'argv', argv), patch.object(matrix, 'inventory') as inventory, \
                    patch.object(matrix, 'command') as command:
                with self.assertRaises(matrix.QueueStopped):
                    matrix.main()
                inventory.assert_not_called()
                command.assert_not_called()
            self.assertEqual(marker.read_bytes(), before)
            self.assertFalse((root / 'next').exists())

    def test_missing_device_set_rejected_before_inventory(self):
        with patch.object(matrix.sys, 'argv', ['matrix']), \
                patch.object(matrix, 'inventory') as inventory, \
                patch.object(matrix.sys, 'stderr', new_callable=io.StringIO) as stderr:
            with self.assertRaises(SystemExit) as stopped:
                matrix.main()
            self.assertEqual(stopped.exception.code, 2)
            inventory.assert_not_called()
            self.assertIn('--device-set are required', stderr.getvalue())

    def test_custom_set_pid_proof_rejects_same_udid_from_other_root(self):
        own = '/Volumes/data/matrix-devices/OWN/data/Containers/Bundle/Application/A/App.app/App'
        for identity, accepted in [(own, True), (own.replace('matrix-devices', 'foreign'), False)]:
            with self.subTest(identity=identity), \
                    patch.object(matrix, 'DEVICE_SET', Path('/Volumes/data/matrix-devices'), create=True), \
                    patch.object(matrix, 'command', side_effect=['su.linka.pictures: 123\n', identity]), \
                    patch.object(matrix.os, 'kill'), \
                    patch.object(matrix.time, 'monotonic', side_effect=[0, 4]):
                if accepted:
                    self.assertEqual(matrix.launch_probe('OWN', 'su.linka.pictures', 'App', 3,
                                                         Path('/unused'))['status'], 'launch_alive')
                else:
                    with self.assertRaisesRegex(RuntimeError, 'identity not verified'):
                        matrix.launch_probe('OWN', 'su.linka.pictures', 'App', 3, Path('/unused'))

    def test_low_space_blocks_before_any_device_command(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(app=Path(directory) / 'App.app', probe='launch', persist=lambda: None)
            record = dict(status='pending', created_name='owned-name')
            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix.shutil, 'disk_usage', return_value=SimpleNamespace(free=1024)), \
                    patch.object(matrix, 'command') as command:
                matrix.run_pair(args, {}, record, Path(directory) / 'evidence', dict(app_sha256='sha'))
                command.assert_not_called()
            self.assertEqual(record['status'], 'disk_space_blocked')
            self.assertNotIn('created_udid', record)

    def test_default_simctl_arguments_are_unchanged(self):
        process = SimpleNamespace(communicate=lambda **kwargs: (b'ok', None), returncode=0)
        with patch.object(matrix, 'DEVICE_SET', None), patch.object(matrix.subprocess, 'Popen') as popen:
            popen.return_value.__enter__.return_value = process
            matrix.command(['xcrun', 'simctl', 'list', '-j', 'devices'])
            self.assertEqual(popen.call_args.args[0], ['xcrun', 'simctl', 'list', '-j', 'devices'])

    def test_custom_set_crash_reports_are_read_only_from_owned_udid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / 'devices/OWN/data/Library/Logs/CrashReporter'
            reports.mkdir(parents=True)
            (reports / 'App.ips').write_text('su.linka.pictures _' + matrix.LIFECYCLE)
            evidence = root / 'evidence'
            evidence.mkdir()
            with patch.object(matrix, 'DEVICE_SET', root / 'devices'), \
                    patch.object(matrix, 'command', return_value=''):
                self.assertEqual(matrix.crash_evidence('OWN', 'su.linka.pictures', 'App', 0, evidence),
                                 'lifecycle_trap')
            self.assertTrue((evidence / 'App.ips').exists())

    def test_low_space_stops_matrix_after_one_blocked_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            app = root / 'App.app'
            app.mkdir()
            (app / 'App').write_bytes(b'app')
            (app / 'Info.plist').write_bytes(plistlib.dumps(dict(CFBundleIdentifier='su.linka.pictures',
                CFBundleExecutable='App', CFBundleSupportedPlatforms=['iPhoneSimulator'])))
            output = root / 'output'
            pair = dict(runtime='runtime', device_type='phone', runtime_name='iOS', device_name='iPhone')
            argv = ['matrix', '--app', str(app), '--output', str(output), '--phase', 'baseline',
                    '--config', 'Debug', '--probe', 'launch', '--device-set', str(root / 'devices')]
            with patch.object(matrix.sys, 'argv', argv), patch.object(matrix, 'DEVICE_SET', None), \
                    patch.object(matrix, 'external_path', side_effect=lambda path, label: path.resolve()), \
                    patch.object(matrix, 'inventory', return_value=[pair, dict(pair, device_type='ipad')]), \
                    patch.object(matrix.shutil, 'disk_usage', return_value=SimpleNamespace(free=1024)), \
                    patch.object(matrix, 'command') as command, patch('builtins.print'):
                self.assertEqual(matrix.main(), 1)
                command.assert_not_called()
            state = json.loads((output / 'matrix.json').read_text())
            self.assertEqual(len(state['records']), 1)
            self.assertEqual(state['records'][0]['status'], 'disk_space_blocked')
            self.assertIn('blocked_reason', state)
            self.assertEqual(len(state['manifest']['selected']), 2)

    def test_disk_preflight_checks_custom_device_set_volume_too(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'evidence'
            devices = root / 'devices'
            output.mkdir()
            devices.mkdir()
            with patch.object(matrix, 'DEVICE_SET', devices), \
                    patch.object(matrix.shutil, 'disk_usage', side_effect=[
                        SimpleNamespace(free=100 * 1024 ** 3), SimpleNamespace(free=1024)]) as usage:
                with self.assertRaises(matrix.DiskSpaceBlocked):
                    matrix.disk_preflight(output)
                self.assertEqual([call.args[0] for call in usage.call_args_list], [output, devices])

    def test_sigint_records_interruption_and_cleans_owned_device(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(app=Path(directory) / 'App.app', probe='launch', boot_timeout=30,
                                   delete_created=False, persist=lambda: None)
            record = dict(status='pending', created_name='owned-name')
            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'command', side_effect=['12345678-1234-1234-1234-123456789ABC',
                                                                KeyboardInterrupt]), \
                    patch.object(matrix, 'crash_evidence') as crash, \
                    patch.object(matrix, 'cleanup') as cleanup:
                with self.assertRaises(KeyboardInterrupt):
                    matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record,
                                    Path(directory) / 'evidence', dict(app_sha256='sha'))
                crash.assert_not_called()
                cleanup.assert_called_once_with(record, False)
            self.assertEqual(record['status'], 'interrupted')

    def test_inventory_distinct_runtime_identifiers_and_exact_dedup(self):
        devices = [dict(name='iPhone Duo', identifier='phone.duo'),
                   dict(name='iPhone Duo', identifier='phone.duo'),
                   dict(name='iPad Pro', identifier='ipad.pro'),
                   dict(name='Apple TV', identifier='tv')]
        runtimes = [dict(name='iOS 27', identifier=name, platform='iOS',
                         isAvailable=True, supportedDeviceTypes=devices)
                    for name in ('runtime.a', 'runtime.b')]
        runtimes += [dict(runtimes[0], identifier='unavailable', isAvailable=False),
                     dict(runtimes[0], identifier='tv', platform='tvOS')]
        pairs = matrix.matrix(dict(runtimes=runtimes))
        self.assertEqual(len(pairs), 4)
        self.assertEqual({p['runtime'] for p in pairs}, {'runtime.a', 'runtime.b'})
        self.assertEqual(sum(p['device_name'] == 'iPhone Duo' for p in pairs), 2)

    def test_classify_original_assertion_separately(self):
        self.assertEqual(matrix.classify('stack ' + matrix.LIFECYCLE, True), 'lifecycle_trap')
        self.assertEqual(matrix.classify('other assertion', True), 'unknown_crash')
        self.assertEqual(matrix.classify('timeout'), 'infrastructure_blocked')

    def test_full_matrix_reproduction_requires_every_pair_and_real_trap(self):
        manifest = dict(phase='baseline', probe='launch', scope='full', selected=['old', 'new'])
        records = [dict(status='launch_alive'), dict(status='lifecycle_trap')]
        self.assertTrue(matrix.baseline_reproduced(manifest, records))
        for bad in [records[:1], [dict(status='launch_alive')] * 2,
                    [dict(status='infrastructure_blocked'), dict(status='lifecycle_trap')],
                    [dict(status='interrupted'), dict(status='lifecycle_trap')]]:
            self.assertFalse(matrix.baseline_reproduced(manifest, bad))
        self.assertFalse(matrix.baseline_reproduced(dict(manifest, scope='pilot'), records))

    def test_resume_rejects_sha_and_configuration_mismatch(self):
        manifest = dict(app_sha256='original', configuration='Debug', phase='baseline', inventory=[],
                        device_set='/Volumes/data/owned')
        matrix.validate_resume(dict(manifest=manifest), dict(manifest))
        for field, replacement in [('app_sha256', 'changed'), ('configuration', 'Release'),
                                   ('phase', 'fixed'), ('inventory', ['new']),
                                   ('device_set', '/Volumes/data/other')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                matrix.validate_resume(dict(manifest=manifest), dict(manifest, **{field: replacement}))

    def test_pass_requires_positive_count_no_failures_or_skips(self):
        good = dict(totalTestCount=2, passedTests=2, failedTests=0, skippedTests=0, result='Passed')
        self.assertTrue(matrix.tests_pass(good))
        for changes in [dict(totalTestCount=0, passedTests=0), dict(failedTests=1),
                        dict(skippedTests=1), dict(result='Failed'), dict(passedTests=True),
                        dict(testFailures=['crash']), dict(totalTestCount=3)]:
            with self.subTest(changes=changes):
                self.assertFalse(matrix.tests_pass(dict(good, **changes)))
        self.assertFalse(matrix.tests_pass({}))

    def test_inventory_preserves_distinct_builds_with_same_display_name(self):
        runtimes = [dict(name='iOS 27', identifier=identifier, buildversion=build,
                         platform='iOS', isAvailable=True,
                         supportedDeviceTypes=[dict(name='iPhone Duo', identifier='duo')])
                    for identifier, build in [('runtime.a', '24A1'), ('runtime.b', '24A2')]]
        pairs = matrix.matrix(dict(runtimes=runtimes))
        self.assertEqual([(p['runtime'], p['runtime_build']) for p in pairs],
                         [('runtime.a', '24A1'), ('runtime.b', '24A2')])

    def test_early_ps_failure_is_not_launch_alive(self):
        with patch.object(matrix, 'command', side_effect=['su.linka.pictures: 123\n',
                                                          RuntimeError('process exited')]), \
                patch.object(matrix.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'process exited'):
                matrix.launch_probe('OWN-UDID', 'su.linka.pictures', 'Linka', 3, Path('/unused'))
            kill.assert_called_once_with(123, 0)

    def test_pid_identity_must_match_owned_device_before_polling(self):
        with patch.object(matrix, 'command', side_effect=['su.linka.pictures: 123\n',
                                                          '/Devices/FOREIGN/Linka']), \
                patch.object(matrix.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'identity not verified'):
                matrix.launch_probe('OWN-UDID', 'su.linka.pictures', 'Linka', 3, Path('/unused'))
            kill.assert_not_called()

    def test_cleanup_foreign_ledger_never_changes_device(self):
        with patch.object(matrix, 'owned_device', return_value=False), \
                patch.object(matrix, 'command') as command:
            with self.assertRaisesRegex(RuntimeError, 'Cleanup refused'):
                matrix.cleanup(dict(created_udid='FOREIGN', created_name='owned-name'), True)
            command.assert_not_called()

    def test_cleanup_shutdown_retains_owned_device_by_default(self):
        record = dict(created_udid='OWN', created_name='owned-name')
        devices = '{"devices":{"runtime":[{"udid":"OWN","name":"owned-name","state":"Booted"}]}}'
        with patch.object(matrix, 'owned_device', return_value=True), \
                patch.object(matrix, 'command', side_effect=[devices, '']) as command:
            matrix.cleanup(record, False)
            self.assertEqual(command.call_args_list[-1].args[0], ['xcrun', 'simctl', 'shutdown', 'OWN'])
            self.assertEqual(command.call_count, 2)
            self.assertEqual(record['cleanup'], 'shutdown_retained')

    def test_scoped_assertion_log_detects_trap_without_crash_report(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            with patch.object(matrix.Path, 'home', return_value=folder), \
                    patch.object(matrix, 'command', return_value='_' + matrix.LIFECYCLE) as command:
                self.assertEqual(matrix.crash_evidence('OWN', 'su.linka.pictures', 'Linka', 0, folder),
                                 'lifecycle_trap')
                arguments = command.call_args.args[0]
                self.assertEqual(arguments[:5], ['xcrun', 'simctl', 'spawn', 'OWN', 'log'])
                self.assertIn('process == "Linka"', arguments[-1])
                self.assertIn(matrix.LIFECYCLE, arguments[-1])

    def test_exact_batch_counts_and_editor_last(self):
        selector = 'LinkaStartupUITests/MatrixSmokeTests'
        batches = matrix.test_batches(selector)
        self.assertEqual([batch['expected'] for batch in batches], [1] * 8)
        self.assertTrue(all(batch['skip'] is None for batch in batches))
        self.assertEqual([batch['selector'].split('/')[-1] for batch in batches], list(matrix.METHODS))
        self.assertTrue(batches[-1]['selector'].endswith('/' + matrix.EDITOR))
        self.assertEqual(matrix.test_batches(selector + '/testColdLaunchAndRelaunch'),
                         [dict(name='smoke', selector=selector + '/testColdLaunchAndRelaunch',
                               expected=1, skip=None)])
        for invalid in ['Other/MatrixSmokeTests', selector + '/unknown', selector + '/testEditorCreatePublishRelaunchAndCancelCardDraft']:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                matrix.test_batches(invalid)
        summary = dict(passedTests=1, totalTestCount=1, failedTests=0, skippedTests=0, result='Passed')
        self.assertTrue(matrix.tests_pass(summary, 1))
        self.assertFalse(matrix.tests_pass(summary, 7))

    def test_configuration_provenance(self):
        self.assertEqual(matrix.configuration_provenance({}, 'Debug', 'sha', None, False)['source'],
                         'declared_only')
        with self.assertRaises(ValueError):
            matrix.configuration_provenance({}, 'Debug', 'sha', None, True)
        self.assertEqual(matrix.configuration_provenance(dict(LinkaBuildConfiguration='Debug'),
                                                        'Debug', 'sha', None, True)['source'], 'Info.plist')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'build.json'
            path.write_text(json.dumps(dict(configuration='Debug', app_sha256='sha')))
            self.assertEqual(matrix.configuration_provenance({}, 'Debug', 'sha', path, True)['source'],
                             'build_manifest')
            for config, sha in [('Release', 'sha'), ('Debug', 'changed')]:
                with self.subTest(config=config, sha=sha), self.assertRaises(ValueError):
                    matrix.configuration_provenance({}, config, sha, path, True)

    def test_xctestrun_v2_and_legacy_freeze_external_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            host = root / 'Runner.app'
            test = host / 'PlugIns/Smoke.xctest'
            test.mkdir(parents=True)
            (test / 'Smoke').write_bytes(b'tests')
            (host / 'Runner').write_bytes(b'host')
            product = root / 'Frozen.app'
            product.mkdir()
            (product / 'Product').write_bytes(b'app')
            target = dict(BlueprintName='LinkaStartupUITests',
                          TestHostPath='__TESTROOT__/Runner.app',
                          TestBundlePath='__TESTHOST__/PlugIns/Smoke.xctest',
                          UITargetAppPath='__TESTROOT__/Frozen.app',
                          TestingEnvironmentVariables=dict(LINKA_UI_PRODUCT_BUNDLE_ID='su.linka.pictures'))
            path = root / 'Smoke.xctestrun'
            for data in [dict(LinkaStartupUITests=target),
                         dict(TestConfigurations=[dict(TestTargets=[target])])]:
                path.write_bytes(plistlib.dumps(data))
                hashes = matrix.test_artifacts(path, 'su.linka.pictures', matrix.digest(product))
                self.assertEqual(set(hashes), {str(host), str(product)})
                before = hashes[str(host)]
                (test / 'Smoke').write_bytes((test / 'Smoke').read_bytes() + b'changed tests')
                self.assertNotEqual(before, matrix.digest(host))
            for changes in [dict(TestingEnvironmentVariables={}),
                            dict(TestingEnvironmentVariables=dict(LINKA_UI_PRODUCT_BUNDLE_ID='wrong')),
                            dict(TestBundlePath='__UNKNOWN__/Smoke.xctest'),
                            dict(TestHostPath='__TESTROOT__/missing.app'),
                            dict(UITargetAppPath='__TESTROOT__/Runner.app')]:
                path.write_bytes(plistlib.dumps(dict(LinkaStartupUITests=dict(target, **changes))))
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    matrix.test_artifacts(path, 'su.linka.pictures', matrix.digest(product))

    def test_pair_runs_smoke_large_text_then_editor_with_separate_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(app=root / 'App.app', xctestrun=root / 'Smoke.xctestrun',
                                   probe='xctest', boot_timeout=30, test_timeout=60,
                                   delete_created=False, persist=lambda: None)
            manifest = dict(app_sha256='sha', bundle_id='su.linka.pictures', executable='App',
                            test_batches=matrix.test_batches('LinkaStartupUITests/MatrixSmokeTests'))
            record = dict(created_name='owned-name', status='pending')
            calls = []
            category = 'large'

            def command(arguments, **kwargs):
                nonlocal category
                calls.append(list(map(str, arguments)))
                if arguments[1:3] == ['simctl', 'create']:
                    return '12345678-1234-1234-1234-123456789ABC'
                if 'xcresulttool' in arguments:
                    count = 1
                    return json.dumps(dict(passedTests=count, totalTestCount=count, failedTests=0,
                                           skippedTests=0, result='Passed'))
                if 'content_size' in arguments:
                    if arguments[-1] != 'content_size':
                        category = arguments[-1]
                        return ''
                    return category + '\n'
                return ''

            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'verify_test_inputs'), \
                    patch.object(matrix, 'command', side_effect=command), \
                    patch.object(matrix, 'crash_evidence', return_value='infrastructure_blocked'), \
                    patch.object(matrix, 'cleanup') as cleanup:
                matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record,
                                root / 'evidence', manifest)
            self.assertEqual(record['status'], 'tests_passed')
            self.assertEqual(record['expected_test_count'], 8)
            self.assertEqual(record['passed_test_count'], 8)
            self.assertIn('finished_at', record)
            self.assertGreaterEqual(record['duration_seconds'], 0)
            builds = [call for call in calls if call[0] == 'xcodebuild']
            self.assertEqual(len(builds), 8)
            self.assertIn('-only-testing:LinkaStartupUITests/MatrixSmokeTests/' + matrix.LARGE_TEXT, builds[5])
            self.assertIn('-only-testing:LinkaStartupUITests/MatrixSmokeTests/' + matrix.EDITOR, builds[-1])
            self.assertEqual(len({build[build.index('-resultBundlePath') + 1] for build in builds}), 8)
            content_size = record['test_batches'][5]['content_size']
            self.assertEqual(content_size['original'], 'large')
            self.assertEqual(content_size['requested'], 'accessibility-extra-extra-extra-large')
            self.assertEqual(content_size['observed'], content_size['requested'])
            self.assertEqual(content_size['restored'], 'large')
            self.assertEqual(category, 'large')
            cleanup.assert_called_once_with(record, False)

    def test_large_text_readback_mismatch_blocks_test_body_and_restores_category(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = {}
            with patch.object(matrix, 'command', side_effect=['large\n', '', 'extra-large\n', '', 'large\n']) as command:
                with self.assertRaises(matrix.CapabilityBlocked):
                    with matrix.large_text_category('OWN', evidence, Path(directory)):
                        self.fail('LargeText test must not run when OS readback differs')
                self.assertEqual(command.call_args_list[-2].args[0],
                                 ['xcrun', 'simctl', 'ui', 'OWN', 'content_size', 'large'])
            self.assertEqual(evidence['content_size']['restored'], 'large')

    def test_large_text_restores_category_after_test_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = {}
            with patch.object(matrix, 'command', side_effect=['medium\n', '',
                    'accessibility-extra-extra-extra-large\n', '', 'medium\n']):
                with self.assertRaisesRegex(RuntimeError, 'test failed'):
                    with matrix.large_text_category('OWN', evidence, Path(directory)):
                        raise RuntimeError('test failed')
            self.assertEqual(evidence['content_size']['restored'], 'medium')

    def test_single_large_text_mismatch_is_capability_blocked_without_xcodebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(app=root / 'App.app', xctestrun=root / 'Smoke.xctestrun',
                                   probe='xctest', boot_timeout=30, test_timeout=60,
                                   delete_created=False, persist=lambda: None)
            selector = 'LinkaStartupUITests/MatrixSmokeTests/testLargeTextLibraryAndCommunicationHitTargets'
            manifest = dict(app_sha256='sha', bundle_id='su.linka.pictures', executable='App',
                            test_batches=matrix.test_batches(selector))
            record = dict(created_name='owned-name', status='pending')
            calls = []

            def command(arguments, **kwargs):
                calls.append(arguments)
                if arguments[1:3] == ['simctl', 'create']:
                    return '12345678-1234-1234-1234-123456789ABC'
                if arguments[-1] == 'content_size':
                    return 'large\n'
                return ''

            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'verify_test_inputs'), \
                    patch.object(matrix, 'command', side_effect=command), \
                    patch.object(matrix, 'crash_evidence', return_value='infrastructure_blocked'), \
                    patch.object(matrix.time, 'monotonic', side_effect=[0, 0, 11, 12]), \
                    patch.object(matrix, 'cleanup'):
                matrix.run_pair(args, dict(device_type='phone', runtime='runtime'), record,
                                root / 'evidence', manifest)
            self.assertEqual(record['status'], 'capability_blocked')
            self.assertEqual(record['test_batches'][0]['status'], 'capability_blocked')
            self.assertFalse(any(call[0] == 'xcodebuild' for call in calls))
            self.assertEqual(record['test_batches'][0]['content_size']['restored'], 'large')

    def test_large_text_restore_mismatch_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = {}
            with patch.object(matrix, 'command', side_effect=['large', '', matrix.LARGE_CATEGORY,
                                                              '', 'medium']):
                with self.assertRaisesRegex(matrix.CapabilityBlocked, 'restoration readback mismatch'):
                    with matrix.large_text_category('OWN', evidence, Path(directory)):
                        pass
            self.assertEqual(evidence['content_size']['restored'], 'medium')

    def test_large_text_unsupported_capability_does_not_run_or_set(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(matrix, 'command', side_effect=RuntimeError('unsupported')) as command:
                with self.assertRaises(matrix.CapabilityBlocked):
                    with matrix.large_text_category('OWN', {}, Path(directory)):
                        self.fail('Unsupported category must not run a test')
                self.assertEqual(command.call_count, 1)
                self.assertEqual(command.call_args.args[0],
                                 ['xcrun', 'simctl', 'ui', 'OWN', 'content_size'])

    def test_changed_test_artifact_blocks_before_device_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(app=Path(directory) / 'App.app', probe='xctest', persist=lambda: None)
            record = dict(status='pending', created_name='owned-name')
            with patch.object(matrix, 'digest', return_value='sha'), \
                    patch.object(matrix, 'verify_test_inputs', side_effect=RuntimeError('artifact changed')), \
                    patch.object(matrix, 'command') as command:
                matrix.run_pair(args, {}, record, Path(directory) / 'evidence', dict(app_sha256='sha'))
                command.assert_not_called()
            self.assertEqual(record['status'], 'infrastructure_blocked')
            self.assertNotIn('created_udid', record)


if __name__ == '__main__':
    unittest.main()
