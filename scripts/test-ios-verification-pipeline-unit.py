#!/usr/bin/env python3
"""Mock-only pipeline checks: no native commands or real pipeline execution."""
import argparse
from contextlib import redirect_stdout
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location(
    'pipeline', Path(__file__).with_name('test-ios-verification-pipeline.py'))
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.matrix = pipeline.matrix_module()
        # Synthetic evidence only, under the external project volume.
        self.temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.args = argparse.Namespace(output_root=root / 'output', device_set=root / 'devices')
        for key in pipeline.INPUTS:
            setattr(self.args, key, root / key)
        self.state = pipeline.plan(self.args)

    def evidence(self, baseline=True):
        pair = dict(runtime='synthetic-ios', device_type='synthetic-iphone')
        manifest = dict(scope='full', phase='baseline' if baseline else 'fixed',
                        probe='launch' if baseline else 'xctest',
                        configuration='Release' if baseline else 'Debug',
                        selected=[pair], inventory=[pair])
        record = dict(pair=pair, status='lifecycle_trap' if baseline else 'tests_passed')
        if not baseline:
            batches = self.matrix.test_batches('LinkaStartupUITests/MatrixSmokeTests')
            manifest['test_batches'] = batches
            summary = dict(passedTests=1, totalTestCount=1, failedTests=0, skippedTests=0, result='Passed')
            record.update(expected_test_count=8, passed_test_count=8,
                          test_batches=[dict(batch=batch, status='tests_passed', summary=summary)
                                        for batch in batches])
        return dict(manifest=manifest, records=[record], full_matrix_bug_reproduced=baseline,
                    outcome='evidence_only_or_incomplete' if baseline else 'tests_passed')

    def publish(self, index, evidence):
        path = Path(self.state['stages'][index]['matrix_path'])
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(evidence))

    def test_red_then_fixed_failure_never_starts_release(self):
        calls = []

        def child(command):
            calls.append(command)
            if len(calls) == 1:
                self.publish(0, self.evidence())
            else:
                evidence = self.evidence(False)
                evidence['records'][0]['status'] = 'tests_failed'
                self.publish(1, evidence)
            return 1

        with patch.object(pipeline, 'execute_stage', side_effect=child):
            self.assertEqual(pipeline.run(self.args, self.state, self.matrix), 1)
        self.assertEqual(len(calls), 2)
        saved = json.loads((self.args.output_root / 'pipeline.json').read_text())
        self.assertEqual(saved['failed_stage'], 'fixed-debug')
        self.assertEqual([s['status'] for s in saved['stages']], ['passed', 'stopped', 'queued'])
        self.assertIsNone(saved['eta_seconds'])
        self.assertIsNone(saved['stages'][2]['eta_seconds'])

    def test_existing_marker_starts_no_child(self):
        self.args.output_root.mkdir()
        (self.args.output_root / 'matrix-stop.json').write_text('{"status":"tests_failed"}')
        with patch.object(pipeline, 'execute_stage') as child:
            self.assertEqual(pipeline.run(self.args, self.state, self.matrix), 1)
            child.assert_not_called()
        self.assertEqual(self.state['failed_stage'], 'baseline-release')

    def test_full_red_then_both_fixed_pass(self):
        calls = []

        def child(command):
            index = len(calls)
            calls.append(command)
            evidence = self.evidence(index == 0)
            if index == 2:
                evidence['manifest']['configuration'] = 'Release'
            self.publish(index, evidence)
            return 1 if index == 0 else 0

        with patch.object(pipeline, 'execute_stage', side_effect=child):
            self.assertEqual(pipeline.run(self.args, self.state, self.matrix), 0)
        self.assertEqual(len(calls), 3)
        self.assertEqual(self.state['status'], 'passed')

    def test_marker_after_baseline_blocks_debug_even_with_expected_red(self):
        def child(command):
            self.publish(0, self.evidence())
            (self.args.output_root / 'matrix-stop.json').write_text('{}')
            return 1

        with patch.object(pipeline, 'execute_stage', side_effect=child) as process:
            self.assertEqual(pipeline.run(self.args, self.state, self.matrix), 1)
        self.assertEqual(process.call_count, 1)
        self.assertEqual(self.state['stages'][1]['status'], 'queued')

    def test_existing_evidence_is_never_resumed(self):
        evidence = self.evidence()
        evidence['records'] = []
        self.publish(0, evidence)
        with patch.object(pipeline, 'execute_stage', return_value=1) as child:
            with self.assertRaises(ValueError):
                pipeline.run(self.args, self.state, self.matrix)
            child.assert_not_called()  # Existing evidence is refused, never resumed.

    def test_incomplete_evidence_from_child_stops_queue(self):
        def child(command):
            evidence = self.evidence()
            evidence['records'] = []
            self.publish(0, evidence)
            return 1

        with patch.object(pipeline, 'execute_stage', side_effect=child) as process:
            self.assertEqual(pipeline.run(self.args, self.state, self.matrix), 1)
            self.assertEqual(process.call_count, 1)
        self.assertEqual(self.state['stages'][1]['status'], 'queued')

    def test_baseline_exception_is_only_full_trap_evidence(self):
        for change in ('no_trap', 'pilot', 'false_flag', 'wrong_exit', 'blocked'):
            with self.subTest(change=change):
                evidence = self.evidence()
                code = 1
                if change == 'no_trap':
                    evidence['records'][0]['status'] = 'launch_alive'
                elif change == 'pilot':
                    evidence['manifest']['scope'] = 'pilot'
                elif change == 'false_flag':
                    evidence['full_matrix_bug_reproduced'] = False
                elif change == 'wrong_exit':
                    code = 0
                else:
                    evidence['blocked_reason'] = 'failure'
                with self.assertRaises(ValueError):
                    pipeline.verify(self.state['stages'][0], evidence, code, self.matrix)

    def test_fixed_requires_all_eight_actual_passes(self):
        evidence = self.evidence(False)
        pipeline.verify(self.state['stages'][1], evidence, 0, self.matrix)
        for mutate in ('count', 'summary', 'batch'):
            broken = deepcopy(evidence)
            record = broken['records'][0]
            if mutate == 'count':
                record['passed_test_count'] = 7
            elif mutate == 'summary':
                record['test_batches'][0]['summary']['skippedTests'] = 1
            else:
                record['test_batches'].pop()
            with self.assertRaises(ValueError):
                pipeline.verify(self.state['stages'][1], broken, 0, self.matrix)

    def test_prepare_has_no_subprocess_or_writes(self):
        argv = ['--prepare']
        for key in (*pipeline.INPUTS, 'output_root', 'device_set'):
            argv += ['--' + key.replace('_', '-'), str(getattr(self.args, key))]
        with patch.object(pipeline, 'validate') as validate, \
                patch.object(pipeline.subprocess, 'Popen') as child, \
                patch.object(Path, 'write_text') as write, \
                patch.object(Path, 'mkdir') as mkdir, redirect_stdout(io.StringIO()) as output:
            self.assertEqual(pipeline.main(argv), 0)
        child.assert_not_called()
        write.assert_not_called()
        mkdir.assert_not_called()
        self.assertFalse(validate.call_args.kwargs['frozen'])
        state = json.loads(output.getvalue())
        self.assertEqual(len(state['stages']), 3)
        self.assertTrue(all(s['command'][1] == str(pipeline.MATRIX) for s in state['stages']))

    def test_interrupt_signals_child_group_and_waits(self):
        process = Mock(pid=12345)
        process.wait.side_effect = [KeyboardInterrupt(), subprocess.TimeoutExpired('mock', 20), 130]
        with patch.object(pipeline.subprocess, 'Popen') as popen, patch.object(pipeline.os, 'killpg') as kill:
            popen.return_value.__enter__.return_value = process
            with self.assertRaises(KeyboardInterrupt):
                pipeline.execute_stage(['mock-only'])
        self.assertEqual(kill.call_args_list[0].args, (12345, signal.SIGINT))
        self.assertEqual(kill.call_args_list[1].args, (12345, signal.SIGTERM))
        self.assertTrue(popen.call_args.kwargs['start_new_session'])

    def test_live_status_uses_pure_matrix_progress(self):
        self.publish(0, self.evidence())
        self.state['status'] = 'running'
        self.state['stages'][0]['status'] = 'running'
        path = self.args.output_root / 'pipeline.json'
        path.write_text(json.dumps(self.state))
        with patch.object(self.matrix, 'external_path'), \
                patch.object(self.matrix, 'progress', return_value={'eta_seconds': 42}) as progress, \
                patch.object(pipeline.subprocess, 'Popen') as child:
            state = pipeline.status(path, self.matrix)
        progress.assert_called_once()
        child.assert_not_called()
        self.assertEqual(state['stages'][0]['eta_seconds'], 42)
        self.assertIsNone(state['stages'][1]['eta_seconds'])
        self.assertIsNone(state['eta_seconds'])


if __name__ == '__main__':
    unittest.main()
