#!/usr/bin/env python3
"""Offline checks only: no store, GitHub, credentials or APK downloads."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('rustore', Path(__file__).with_name('publish-rustore.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class SafetyTests(unittest.TestCase):
    def test_receipt_requires_success_exact_version_sha(self):
        receipt = dict(status='EDIT_COMMITTED_FOR_REVIEW', outcome='EDIT_COMMITTED_FOR_REVIEW',
                       package=r.PACKAGE, version_code=7, release_version='2.0.1', source_sha='a' * 40)
        r.validate_receipt(receipt, 'a' * 40)
        for key, value in [('status', 'COMMIT_RESULT_UNKNOWN'), ('outcome', 'FAILED'),
                           ('version_code', 6), ('source_sha', 'b' * 40), ('package', 'other')]:
            with self.subTest(key=key), self.assertRaises(r.PublishError):
                r.validate_receipt(dict(receipt, **{key: value}), 'a' * 40)

    def test_no_split_standalone_or_unknown_certificate_fallback(self):
        for groups in [[], [{'certificateSha256Hash': r.NEW_CERT, 'generatedStandaloneApks': [{}]}],
                       [{'certificateSha256Hash': 'f' * 64, 'generatedUniversalApk': {'downloadId': 'x'}}]]:
            with self.assertRaises(r.PublishError):
                r.select_universal({'generatedApks': groups})
        group = lambda cert: {'certificateSha256Hash': cert, 'generatedUniversalApk': {'downloadId': cert}}
        self.assertEqual(r.select_universal({'generatedApks': [group(r.OLD_CERT), group(r.NEW_CERT)]}),
                         (r.NEW_CERT, r.NEW_CERT))
        with self.assertRaises(r.PublishError):
            r.select_universal({'generatedApks': [group(r.NEW_CERT), group(r.NEW_CERT)]})

    def test_pending_and_unknown_status_stop_before_creation(self):
        for status in ['MODERATION', 'TAKEN_FOR_MODERATION', 'AUTO_CHECK', 'DRAFT',
                       'READY_FOR_PUBLICATION', 'PARTIAL_ACTIVE', 'UNKNOWN', 'REJECTED_BY_MODERATOR']:
            page = {'content': [{'versionStatus': status, 'versionCode': 6}],
                    'totalElements': 1, 'totalPages': 1}
            with patch.object(r, 'rustore', return_value=page) as api:
                with self.assertRaises(r.PublishError):
                    r.preflight('synthetic-token')
                api.assert_called_once()
                self.assertNotIn('POST', api.call_args.args)

    def test_preflight_preserves_active_metadata_and_app_scope(self):
        page = {'content': [{'versionStatus': 'ACTIVE', 'versionCode': 6, 'versionId': 42,
                             'testingType': None}], 'totalElements': 1, 'totalPages': 1}
        detail = {'appId': 2063785208, 'paid': False, 'ageLegal': '0+', 'appType': 'MAIN', 'categories': ['tools']}
        with patch.object(r, 'rustore', side_effect=[page, detail]) as api:
            self.assertEqual(r.preflight('synthetic-token'), 42)
            self.assertEqual(api.call_args.args[0], r.DETAIL + '/version/42')
        for changed in [dict(detail, appId=1), dict(detail, paid=True)]:
            with patch.object(r, 'rustore', side_effect=[page, changed]), self.assertRaises(r.PublishError):
                r.preflight('synthetic-token')
        with patch.object(r, 'rustore', return_value=dict(page, totalPages=2)), self.assertRaises(r.PublishError):
            r.preflight('synthetic-token')

    def test_auth_uses_rsa_sha512_key_id_timestamp_not_local_jwe(self):
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        private = base64.b64encode(key.private_bytes(serialization.Encoding.DER,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption())).decode()
        def auth(url, **kwargs):
            body = kwargs['body']
            self.assertEqual(body['keyId'], '2351035158')
            key.public_key().verify(base64.b64decode(body['signature']),
                (body['keyId'] + body['timestamp']).encode(), padding.PKCS1v15(), hashes.SHA512())
            return {'jwe': 'synthetic-jwe'}
        with patch.dict(os.environ, RUSTORE_API_KEY_ID='2351035158', RUSTORE_API_PRIVATE_KEY=private), \
                patch.object(r, 'rustore', side_effect=auth):
            self.assertEqual(r.authenticate(), 'synthetic-jwe')

    def test_http_failure_does_not_leak_or_retry(self):
        error = urllib.error.HTTPError('https://example.invalid/?secret=synthetic-secret', 403,
                                      'synthetic-secret', {}, None)
        with patch.object(r.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(r.PublishError) as caught:
                r.rustore(r.APP, 'synthetic-secret', 'POST', {})
            self.assertNotIn('synthetic-secret', str(caught.exception))
            self.assertEqual(opener.return_value.open.call_count, 1)

    def test_apk_requires_both_sdk_signers_and_exact_package_version(self):
        args = SimpleNamespace(aapt='aapt', apksigner='apksigner', apk=Path('synthetic.apk'))
        result = lambda output: SimpleNamespace(returncode=0, stdout=output)
        outputs = [result("package: name='su.linka.pictures' versionCode='7' versionName='2.0.1' platformBuildVersionName='15'"),
                   result('Signer #1 certificate SHA-256 digest: ' + r.OLD_CERT),
                   result('Signer #1 certificate SHA-256 digest: ' + r.NEW_CERT)]
        with patch.object(r.subprocess, 'run', side_effect=outputs) as run:
            r.verify_apk(args)
            self.assertEqual(run.call_count, 3)
        with patch.object(r.subprocess, 'run', side_effect=[outputs[0], outputs[2]]), self.assertRaises(r.PublishError):
            r.verify_apk(args)

    def test_source_rejects_other_workflow_before_other_requests(self):
        args = SimpleNamespace(google_run_id='123', source_sha='a' * 40, repository='owner/repo')
        with patch.object(r.play, 'request', return_value={'path': '.github/workflows/android.yml'}) as api:
            with self.assertRaises(r.PublishError):
                r.verify_source(args)
            api.assert_called_once()

    def test_uncertain_commit_preserves_phase_and_never_retries_or_cancels(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(apk=Path('synthetic.apk'), evidence=Path(directory) / 'evidence.json')
            detail = {'packages': [{'verCode': 7, 'verName': '2.0.1'}],
                      'publishType': 'INSTANTLY', 'partialValue': 100}
            evidence = {'status': 'GOOGLE_APK_VERIFIED'}
            with patch.object(r, 'authenticate', return_value='synthetic-secret'), \
                    patch.object(r, 'preflight', return_value=42), \
                    patch.object(r, 'rustore', side_effect=[43, {}, detail, r.PublishError('Unknown commit')]) as api:
                with self.assertRaises(r.PublishError):
                    r.publish(args, evidence)
                self.assertEqual(api.call_count, 4)
                self.assertTrue(api.call_args.args[0].endswith('/43/commit?priorityUpdate=0'))
                self.assertEqual(json.loads(args.evidence.read_text())['status'], 'COMMIT_RESULT_UNKNOWN')
                self.assertNotIn('synthetic-secret', args.evidence.read_text())
                create = api.call_args_list[0].args[3]
                self.assertEqual(create['publishType'], 'INSTANTLY')
                self.assertNotIn('privacyExplanation', create)

    def test_prepare_calls_no_external_service(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / 'evidence.json'
            argv = ['publish-rustore.py', '--prepare', '--google-run-id', '123',
                    '--source-sha', 'a' * 40, '--repository', 'owner/repo', '--evidence', str(evidence)]
            with patch.object(r.sys, 'argv', argv), patch.object(r, 'verify_source') as source, \
                    patch.object(r, 'rustore') as api, patch('builtins.print'):
                self.assertEqual(r.main(), 0)
                source.assert_not_called()
                api.assert_not_called()
                self.assertEqual(json.loads(evidence.read_text())['status'], 'PREPARE_NO_API_CALLS')


if __name__ == '__main__':
    unittest.main()
