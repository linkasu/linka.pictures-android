#!/usr/bin/env python3
"""Offline publication contract checks; no credentials, gcloud or network needed."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('publish_google_play', Path(__file__).with_name('publish-google-play.py'))
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.aab = root / 'release.aab'
        self.aab.write_bytes(b'offline synthetic bundle')
        self.args = SimpleNamespace(package=publish.PACKAGE, version_code=7, release_version='2.0.1',
            source_run_id='123', source_sha='a' * 40, repository='owner/repo',
            aab=self.aab, evidence=root / 'evidence.json')
        self.evidence = {'aab_sha256': publish.hashlib.sha256(self.aab.read_bytes()).hexdigest()}
        self.track = {'track': 'production', 'releases': [{'versionCodes': ['6'], 'status': 'completed'}]}

    def responses(self):
        return [{'id': 'edit-1'}, self.track,
                {'versionCode': 7, 'sha256': self.evidence['aab_sha256']}, {}, {}, {'id': 'edit-1'},
                {'generatedApks': []}]

    def source_responses(self):
        return [dict(head_branch='main', head_sha=self.args.source_sha,
                     path='.github/workflows/android.yml', status='completed', conclusion='success',
                     event='workflow_dispatch', repository={'full_name': 'owner/repo'},
                     head_repository={'full_name': 'owner/repo'}),
                {'total_count': 3, 'jobs': [{'name': name, 'conclusion': 'success'}
                    for name in ('build', 'android-e2e', 'bundle-release')]},
                {'total_count': 1, 'artifacts': [{'id': 42, 'name': 'android-release-aab', 'expired': False}]}]

    def test_only_approved_identity(self):
        publish.validate_inputs(self.args)
        for field, value in [('package', 'foreign.app'), ('version_code', 8),
                             ('release_version', '2.0.0'), ('source_sha', 'main'),
                             ('source_run_id', 'latest'), ('repository', '../repo')]:
            args = SimpleNamespace(**vars(self.args))
            setattr(args, field, value)
            with self.subTest(field=field), self.assertRaises(publish.PublishError):
                publish.validate_inputs(args)

    def test_source_exact_jobs_and_artifact(self):
        with patch.object(publish, 'request', side_effect=self.source_responses()) as api:
            result = publish.verify_source(self.args)
        self.assertEqual(result['artifact_id'], 42)
        self.assertEqual(api.call_count, 3)

    def test_reject_untrusted_source_and_skipped_jobs(self):
        for key, value in [('head_branch', 'feature'), ('head_sha', 'b' * 40),
                           ('path', '.github/workflows/other.yml'), ('conclusion', 'failure'),
                           ('head_repository', {'full_name': 'fork/repo'}), ('event', 'pull_request')]:
            responses = self.source_responses()
            responses[0][key] = value
            with self.subTest(key=key), patch.object(publish, 'request', side_effect=responses):
                with self.assertRaises(publish.PublishError):
                    publish.verify_source(self.args)
        for name in ('build', 'android-e2e', 'bundle-release'):
            responses = self.source_responses()
            next(job for job in responses[1]['jobs'] if job['name'] == name)['conclusion'] = 'skipped'
            with patch.object(publish, 'request', side_effect=responses), self.assertRaises(publish.PublishError):
                publish.verify_source(self.args)

    def test_reject_expired_or_missing_artifact(self):
        for artifacts in ([], [{'id': 42, 'name': 'android-release-aab', 'expired': True}]):
            responses = self.source_responses()
            responses[2]['artifacts'] = artifacts
            with patch.object(publish, 'request', side_effect=responses), self.assertRaises(publish.PublishError):
                publish.verify_source(self.args)

    def test_completed_release_validated_before_one_commit(self):
        with patch.object(publish, 'request', side_effect=self.responses()) as api:
            publish.publish(self.args, self.evidence)
        self.assertEqual(self.evidence['status'], 'EDIT_COMMITTED_FOR_REVIEW')
        self.assertEqual(self.evidence['universal_apk_status'], 'NOT_AVAILABLE')
        calls = api.call_args_list
        self.assertIn('/upload/androidpublisher/v3/', calls[2].args[0])
        self.assertEqual(calls[3].args[3]['releases'][0]['status'], 'completed')
        self.assertTrue(calls[4].args[0].endswith(':validate'))
        self.assertIn('changesNotSentForReview=false', calls[5].args[0])
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', calls[5].args[0])
        self.assertEqual(sum(':commit' in call.args[0] for call in calls), 1)

    def test_newer_same_or_staged_release_blocks_before_upload(self):
        for release in ({'versionCodes': ['7'], 'status': 'completed'},
                        {'versionCodes': ['8'], 'status': 'completed'},
                        {'versionCodes': ['6'], 'status': 'inProgress'},
                        {'versionCodes': ['6'], 'status': 'halted'},
                        {'versionCodes': ['6'], 'status': 'draft'},
                        {'versionCodes': ['6'], 'status': 'completed', 'countryTargeting': {'countries': ['RU']}}):
            self.track['releases'] = [release]
            with patch.object(publish, 'request', side_effect=self.responses()) as api:
                with self.assertRaises(publish.PublishError):
                    publish.publish(self.args, self.evidence)
                self.assertEqual(api.call_count, 2)

    def test_wrong_uploaded_version_or_hash_never_commits(self):
        for field, value in [('versionCode', 8), ('sha256', 'wrong')]:
            responses = self.responses()
            responses[2][field] = value
            with patch.object(publish, 'request', side_effect=responses) as api:
                with self.assertRaises(publish.PublishError):
                    publish.publish(self.args, self.evidence)
                self.assertEqual(api.call_count, 3)

    def test_commit_failure_never_retries_and_persists_unknown_outcome(self):
        for error in (publish.UncertainRequest('timeout'), publish.ApiError('HTTP 400')):
            responses = self.responses()
            responses[5] = error
            with patch.object(publish, 'request', side_effect=responses) as api:
                with self.assertRaises(publish.PublishError):
                    publish.publish(self.args, self.evidence)
                self.assertEqual(api.call_count, 6)
                self.assertEqual(json.loads(self.args.evidence.read_text())['status'], 'COMMIT_RESULT_UNKNOWN')

    def test_validation_failure_never_commits(self):
        responses = self.responses()
        responses[4] = publish.ApiError('HTTP 400')
        with patch.object(publish, 'request', side_effect=responses) as api:
            with self.assertRaises(publish.PublishError):
                publish.publish(self.args, self.evidence)
        self.assertEqual(api.call_count, 5)

    def test_wrong_app_id_blocks_main_before_any_api_call(self):
        argv = ['publish-google-play.py', '--package', 'foreign.app', '--source-run-id', '123',
                '--source-sha', self.args.source_sha, '--repository', self.args.repository,
                '--version-code', '7', '--release-version', '2.0.1', '--aab', str(self.aab),
                '--evidence', str(self.args.evidence)]
        with patch.object(publish.sys, 'argv', argv), patch.object(publish, 'request') as api:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(publish.main(), 1)
            api.assert_not_called()

    def test_generated_apk_query_failure_does_not_hide_successful_commit(self):
        responses = self.responses()
        responses[-1] = publish.ApiError('HTTP 404')
        with patch.object(publish, 'request', side_effect=responses):
            publish.publish(self.args, self.evidence)
        self.assertEqual(self.evidence['status'], 'EDIT_COMMITTED_FOR_REVIEW')
        self.assertEqual(self.evidence['universal_apk_status'], 'QUERY_FAILED_NOT_DOWNLOADED')

    def test_http_error_redacted_and_not_retried(self):
        opener = unittest.mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError('https://example.test', 403, 'SECRET', {}, io.BytesIO(b'SECRET'))
        with patch.object(publish.urllib.request, 'build_opener', return_value=opener):
            with self.assertRaises(publish.ApiError) as caught:
                publish.request('https://example.test', 'SECRET')
        self.assertNotIn('SECRET', str(caught.exception))
        self.assertEqual(opener.open.call_count, 1)

    def test_dry_run_makes_no_external_calls(self):
        argv = ['publish-google-play.py', '--dry-run', '--source-run-id', '123',
                '--source-sha', self.args.source_sha, '--repository', self.args.repository,
                '--version-code', '7', '--release-version', '2.0.1', '--aab', str(self.aab),
                '--evidence', str(self.args.evidence)]
        with patch.object(publish.sys, 'argv', argv), patch.object(publish, 'request') as api:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(publish.main(), 0)
            api.assert_not_called()
        self.assertIn('DRY_RUN', json.loads(self.args.evidence.read_text())['status'])


if __name__ == '__main__':
    unittest.main()
