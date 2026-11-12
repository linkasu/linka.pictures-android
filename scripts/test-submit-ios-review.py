#!/usr/bin/env python3
"""Offline checks only: no credentials, Apple calls, or workflow dispatch."""

import contextlib
import importlib.util
import io
import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('submit_ios', Path(__file__).with_name('submit-ios-review.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def version(state='REJECTED', text='2.0.0', identifier='version'):
    return dict(id=identifier, attributes=dict(platform='IOS', versionString=text, appVersionState=state))


class FakeAPI:
    def __init__(self, compliance=None, state='VALID', submission_state='UNRESOLVED_ISSUES'):
        self.calls = []
        self.compliance = compliance
        self.state = state
        self.submission_state = submission_state
        self.notes = ''

    def get(self, path, **query):
        if path == '/v1/builds':
            return dict(data=[dict(id='build', attributes=dict(processingState=self.state, usesNonExemptEncryption=self.compliance), relationships=dict(preReleaseVersion=module.related('preReleaseVersions', 'pre')))], included=[dict(type='preReleaseVersions', id='pre', attributes=dict(version='2.0.0', platform='IOS'))])
        if path.endswith('/appStoreReviewDetail'):
            return dict(data=dict(id='detail', type='appStoreReviewDetails', attributes=dict(notes=self.notes, contactEmail='unchanged@example.invalid')))
        if path.endswith('/build'):
            return dict(data=dict(id='build'))
        if path == '/v1/apps/' + module.APP:
            return dict(data=dict(attributes=dict(bundleId=module.BUNDLE)))
        if path == '/v1/reviewSubmissions/submission':
            return dict(data=dict(attributes=dict(state='WAITING_FOR_REVIEW')))
        if path == '/v1/appStoreVersions/version':
            return dict(data=version('WAITING_FOR_REVIEW'))
        raise AssertionError(path)

    def all(self, path, **query):
        if path.endswith('/appStoreVersions'):
            return [version()]
        if path.endswith('/reviewSubmissions'):
            return [dict(id='submission', attributes=dict(state=self.submission_state, platform='IOS'))]
        if path.endswith('/items'):
            return [dict(id='item', attributes=dict(state='REJECTED'), relationships=dict(appStoreVersion=module.related('appStoreVersions', 'version')))]
        raise AssertionError(path)

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        return {}

    def patch(self, kind, identifier, **attributes):
        self.calls.append(('PATCH', kind, attributes))
        if kind == 'appStoreReviewDetails':
            self.notes = attributes['notes']


class Tests(unittest.TestCase):
    def test_jwt_has_raw_es256_signature(self):
        try:
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import ec, utils
        except ImportError:
            self.skipTest('cryptography installed in submit CI, not required for offline prepare')
        key = ec.generate_private_key(ec.SECP256R1())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.p8'
            path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            with patch.dict(module.os.environ, dict(ASC_KEY_PATH=str(path), ASC_API_KEY_ID='TESTKEY', ASC_API_ISSUER_ID='00000000-0000-0000-0000-000000000000')):
                token = module.jwt_token()
            header, claims, signature = token.split('.')
            raw = base64.urlsafe_b64decode(signature + '=' * (-len(signature) % 4))
            self.assertEqual(len(raw), 64)
            der = utils.encode_dss_signature(int.from_bytes(raw[:32], 'big'), int.from_bytes(raw[32:], 'big'))
            key.public_key().verify(der, (header + '.' + claims).encode(), ec.ECDSA(hashes.SHA256()))
            payload = json.loads(base64.urlsafe_b64decode(claims + '=' * (-len(claims) % 4)))
            self.assertEqual(payload['aud'], 'appstoreconnect-v1')
            self.assertLessEqual(payload['exp'] - payload['iat'], 1200)

    def test_default_is_offline(self):
        with patch.object(sys, 'argv', ['submit-ios-review.py']), patch.object(module, 'API', side_effect=AssertionError('network')), contextlib.redirect_stdout(io.StringIO()) as output:
            module.main()
        self.assertIn('"network": false', output.getvalue())

    def test_rejects_redirect_and_lookalike_hosts(self):
        for path in ('https://evil.invalid/v1/apps', '//evil.invalid/v1/apps', 'https://api.appstoreconnect.apple.com.evil.invalid/v1/apps', 'https://api.appstoreconnect.apple.com:443/v1/apps'):
            with self.assertRaises(module.Blocked):
                module.api_url(path)
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.invalid'))

    def test_editable_exact_version_only(self):
        self.assertEqual(module.choose_version([version()])['id'], 'version')
        for versions in ([version('WAITING_FOR_REVIEW')], [version(), version(text='2.0.1', identifier='new')], [version(), version(text='1.0', identifier='other', state='IN_REVIEW')]):
            with self.assertRaises(module.Blocked):
                module.choose_version(versions)

    def test_compliance_failure_mutates_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeAPI()
            with self.assertRaisesRegex(module.Blocked, 'Export compliance unanswered'):
                module.submit(api, Path(directory), 0)
            self.assertEqual(api.calls, [])
        with self.assertRaisesRegex(module.Blocked, 'not approved'):
            module.wait_build(FakeAPI(compliance=True), 0)

    def test_processing_terminal_and_timeout(self):
        for state in ('FAILED', 'INVALID', 'PROCESSING'):
            with self.assertRaises(module.Blocked):
                module.wait_build(FakeAPI(state=state), 0)

    def test_active_review_mutates_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeAPI(compliance=False, submission_state='IN_REVIEW')
            with self.assertRaisesRegex(module.Blocked, 'active review'):
                module.submit(api, Path(directory), 0)
            self.assertEqual(api.calls, [])

    def test_resolves_original_rejected_item_without_creation(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            api = FakeAPI(compliance=False)
            module.submit(api, Path(directory), 0)
            self.assertEqual([c[0] for c in api.calls], ['PATCH'] * 4)
            self.assertEqual(api.calls[-2], ('PATCH', 'reviewSubmissionItems', dict(resolved=True)))
            self.assertEqual(api.calls[-1], ('PATCH', 'reviewSubmissions', dict(submitted=True)))
            self.assertEqual(set(api.calls[1][2]), {'notes'})
            self.assertTrue((Path(directory) / 'submission-result.json').exists())

    def test_mutation_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeAPI(compliance=False)
            api.request = unittest.mock.Mock(side_effect=module.Blocked('unknown outcome'))
            with self.assertRaises(module.Blocked):
                module.submit(api, Path(directory), 0)
            self.assertEqual(api.request.call_count, 1)

    def test_transport_failure_is_sanitized_and_journaled_once(self):
        with tempfile.TemporaryDirectory() as directory:
            api = module.API(Path(directory))
            api.opener = unittest.mock.Mock()
            api.opener.open.side_effect = module.urllib.error.HTTPError('secret-url', 409, 'secret-detail', {}, None)
            with patch.object(module, 'jwt_token', return_value='synthetic-secret'), self.assertRaises(module.Blocked) as caught:
                api.patch('reviewSubmissions', 'submission', submitted=True)
            self.assertNotIn('secret', str(caught.exception))
            self.assertEqual(api.opener.open.call_count, 1)
            journal = (Path(directory) / 'mutation-journal.jsonl').read_text()
            self.assertEqual(len(journal.splitlines()), 1)
            self.assertNotIn('synthetic-secret', journal)

    def test_processing_wait_rechecks_until_terminal_criterion(self):
        api = FakeAPI(compliance=False, state='PROCESSING')
        # Explicit injected clock/sleep avoids real waits and proves processing is re-read.
        def ready(_):
            api.state = 'VALID'
        self.assertEqual(module.wait_build(api, 20, clock=lambda: 0, sleep=ready)['id'], 'build')

    def test_failed_readback_never_reports_success(self):
        api = FakeAPI(compliance=False)
        original_get = api.get
        api.get = lambda path, **query: dict(data=dict(attributes=dict(state='READY_FOR_REVIEW'))) if path == '/v1/reviewSubmissions/submission' else original_get(path, **query)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(module.Blocked, 'not verified'):
                module.submit(api, Path(directory), 0)
            self.assertFalse((Path(directory) / 'submission-result.json').exists())
            self.assertTrue((Path(directory) / 'submission-intent.json').exists())

    def test_new_submission_uses_current_three_step_flow(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            api = FakeAPI(compliance=False)
            original_all = api.all
            api.all = lambda path, **query: [] if path.endswith('/reviewSubmissions') else original_all(path, **query)
            original_request = api.request
            def request(method, path, body=None):
                original_request(method, path, body)
                return dict(data=dict(id='submission')) if path == '/v1/reviewSubmissions' else {}
            api.request = request
            module.submit(api, Path(directory), 0)
            self.assertEqual([c[0] for c in api.calls], ['PATCH', 'PATCH', 'POST', 'POST', 'PATCH'])
            self.assertEqual(api.calls[2][2]['data']['attributes'], dict(platform='IOS'))
            self.assertEqual(api.calls[3][2]['data']['relationships']['appStoreVersion'], module.related('appStoreVersions', 'version'))


if __name__ == '__main__':
    unittest.main()
