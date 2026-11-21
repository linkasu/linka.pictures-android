#!/usr/bin/env python3
"""Offline safety regressions: no credentials, store calls, or APK downloads."""
import base64
import copy
import importlib.util
import json
import os
from pathlib import Path
import ssl
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

spec = importlib.util.spec_from_file_location('rustore', Path(__file__).with_name('publish-rustore.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class SafetyTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, GITHUB_SHA='d' * 40)
        environment.start()
        self.addCleanup(environment.stop)

    def fixture(self):
        args = SimpleNamespace(draft_id=str(r.REPLACEMENT_ID), template_version_id=str(r.TEMPLATE_ID),
                               apk=Path('synthetic.apk'), source_sha='a' * 40, google_run_id='123')
        page = dict(totalPages=1, totalElements=2, content=[
            dict(versionId=r.TEMPLATE_ID, versionStatus='REJECTED_BY_MODERATOR', versionCode=6, testingType=None),
            dict(versionId=r.REPLACEMENT_ID, versionStatus='DRAFT', versionCode=-1, testingType=None)])
        target = dict(appId=2063785208, appName='Линка', shortDescription='Картинки',
                      fullDescription='Общение', paid=False, ageLegal='0+', appType='MAIN',
                      categories=['tools'], privacyExplanation='synthetic-private',
                      privacyDataTypes=[], developerContacts={'email': 'synthetic-private'},
                      packages=[], publishType='INSTANTLY', partialValue=-1, iconUid=r.MEDIA_PATHS[r.ICON_UID],
                      fileUids=[dict(fileUid=r.MEDIA_PATHS[uid], type='SCREENSHOT', ordinal=index,
                                orientation='LANDSCAPE', deviceType='MOBILE', fileUrl='synthetic-private')
                                for index, uid in enumerate(sorted(r.SCREEN_UIDS))])
        source = copy.deepcopy(target)
        source['packages'] = [dict(verCode=6, verName='2.0.0', apkUid='old-apk')]
        source['fileUids'].append(dict(fileUid='old-apk', type='APK'))
        return args, page, source, target

    def receipt(self, args, source):
        return dict(status='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED',
                    outcome='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED', delivery_sha='d' * 40,
                    source_sha=args.source_sha, google_run_id=args.google_run_id,
                    apk_sha256='f' * 64, package=r.PACKAGE, version_code=8, release_version='2.0.2',
                    app_id=2063785208, template_version_id=r.TEMPLATE_ID,
                    template_listing_sha256=r.listing_hash(source),
                    template_media_sha256=r.field_hash(r.media_snapshot(source)),
                    inspected_draft_id=r.REPLACEMENT_ID)

    def test_rustore_ca_is_pinned_without_global_trust_or_hostname_changes(self):
        before = set(ssl.create_default_context().get_ca_certs(binary_form=True))
        context = r.rustore_ssl_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertEqual(set(ssl.create_default_context().get_ca_certs(binary_form=True)), before)
        self.assertIn('d26d2d0231b7c39f92cc738512ba54103519e4405d68b5bd703e9788ca8ecf31',
                      {r.hashlib.sha256(cert).hexdigest() for cert in context.get_ca_certs(binary_form=True)})
        with patch.object(Path, 'read_text', return_value='-----BEGIN CERTIFICATE-----\nZmFrZQ==\n-----END CERTIFICATE-----'):
            with self.assertRaises((r.PublishError, ValueError)):
                r.rustore_ssl_context()

    def test_sdk_signer_ranges_and_stamp_rejection(self):
        text = (f'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {r.NEW_CERT}\n'
                f'Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {r.OLD_CERT}\n'
                'Source Stamp Signer certificate SHA-256 digest: ' + 'f' * 64 + '\n')
        self.assertEqual(r.signer_hashes(text, 24), [r.OLD_CERT])
        self.assertEqual(r.signer_hashes(text, 33), [r.NEW_CERT])
        for changed in (text.replace(r.NEW_CERT, 'f' * 64), text.replace('maxSdkVersion=32', 'maxSdkVersion=33'),
                        text + f'Signer #1 certificate SHA-256 digest: {r.OLD_CERT}\n'):
            with self.assertRaises(r.PublishError):
                r.signer_hashes(changed, 33)
        self.assertEqual(r.signer_hashes('Source Stamp Signer certificate SHA-256 digest: ' + r.NEW_CERT, 33), [])

    def test_receipts_bind_exact_new_version_and_all_inspection_identities_before_auth(self):
        args, _, source, _ = self.fixture()
        receipt = self.receipt(args, source)
        with tempfile.TemporaryDirectory() as directory:
            args.inspection = Path(directory) / 'inspection.json'
            args.inspection.write_text(json.dumps(receipt))
            self.assertEqual(r.validate_inspection(args, {'apk_sha256': 'f' * 64}), receipt)
            changes = [('status', 'FAILED'), ('outcome', 'FAILED'), ('delivery_sha', 'b' * 40),
                       ('source_sha', 'b' * 40), ('google_run_id', '124'), ('apk_sha256', 'e' * 64),
                       ('template_version_id', 42), ('inspected_draft_id', 43),
                       ('template_listing_sha256', ''), ('template_media_sha256', ''),
                       ('package', 'other'), ('version_code', 7), ('release_version', '2.0.1')]
            for key, value in changes:
                args.inspection.write_text(json.dumps(dict(receipt, **{key: value})))
                with self.subTest(key=key), patch.object(r, 'authenticate') as auth:
                    with self.assertRaises(r.PublishError):
                        r.publish_draft(args, {'apk_sha256': 'f' * 64})
                    auth.assert_not_called()

    def test_google_receipt_rejects_old_or_uncertain_release(self):
        receipt = dict(status='EDIT_COMMITTED_FOR_REVIEW', outcome='EDIT_COMMITTED_FOR_REVIEW',
                       package=r.PACKAGE, version_code=8, release_version='2.0.2', source_sha='a' * 40)
        r.validate_receipt(receipt, 'a' * 40)
        for key, value in [('status', 'COMMIT_RESULT_UNKNOWN'), ('outcome', 'FAILED'), ('version_code', 7),
                           ('release_version', '2.0.1'), ('source_sha', 'b' * 40), ('package', 'other')]:
            with self.subTest(key=key), self.assertRaises(r.PublishError):
                r.validate_receipt(dict(receipt, **{key: value}), 'a' * 40)

    def test_inspect_checks_clone_with_only_reads_and_sanitizes_evidence(self):
        args, page, source, target = self.fixture()
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(r, 'authenticate', return_value='synthetic-token'), \
                patch.object(r, 'rustore', side_effect=[page, source, page, source, target]) as api:
            args.evidence = Path(directory) / 'evidence.json'
            evidence = {}
            r.inspect(args, evidence)
            self.assertNotIn('synthetic-private', args.evidence.read_text())
        self.assertEqual(evidence['inspected_draft_id'], r.REPLACEMENT_ID)
        self.assertEqual(evidence['draft']['package_count'], 0)
        self.assertNotIn('synthetic-private', json.dumps(evidence))
        self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_preflight_blockers_never_mutate(self):
        for case in ('wrong_target', 'wrong_source', 'source_pending', 'source_security_rejection',
                     'source_code', 'source_testing', 'source_apk', 'extra_version', 'existing_target_apk',
                     'target_pending', 'target_testing', 'wrong_app', 'paid', 'metadata', 'missing_privacy',
                     'missing_name', 'media', 'missing_media', 'settings', 'incomplete', 'duplicate_history',
                     'changed_since_inspection'):
            args, page, source, target = self.fixture()
            receipt = self.receipt(args, source)
            if case == 'wrong_target':
                args.draft_id = '43'
            elif case == 'wrong_source':
                args.template_version_id = '42'
            elif case == 'source_pending':
                page['content'][0]['versionStatus'] = 'MODERATION'
            elif case == 'source_security_rejection':
                page['content'][0]['versionStatus'] = 'REJECTED_BY_SECURITY'
            elif case == 'source_code':
                page['content'][0]['versionCode'] = 8
            elif case == 'source_testing':
                page['content'][0]['testingType'] = 'alpha'
            elif case == 'source_apk':
                source['packages'][0]['verCode'] = 8
            elif case == 'extra_version':
                page['content'].append(dict(versionId=99, versionCode=5, versionStatus='ACTIVE'))
                page['totalElements'] = 3
            elif case == 'existing_target_apk':
                target['packages'] = [dict(verCode=7)]
            elif case == 'target_pending':
                page['content'][1]['versionStatus'] = 'TAKEN_FOR_MODERATION'
            elif case == 'target_testing':
                page['content'][1]['testingType'] = 'alpha'
            elif case == 'wrong_app':
                target['appId'] = 1
            elif case == 'paid':
                target['paid'] = True
            elif case == 'metadata':
                target['fullDescription'] = 'Changed'
            elif case == 'missing_privacy':
                del target['privacyExplanation']
            elif case == 'missing_name':
                target['appName'] = ' '
            elif case == 'media':
                target['fileUids'][0]['ordinal'] = 99
            elif case == 'missing_media':
                target['fileUids'].pop()
            elif case == 'settings':
                target['partialValue'] = 50
            elif case == 'incomplete':
                page['totalPages'] = 2
            elif case == 'duplicate_history':
                page['content'][1]['versionId'] = r.TEMPLATE_ID
            elif case == 'changed_since_inspection':
                source['fullDescription'] = 'Changed'
            with self.subTest(case=case), patch.object(r, 'authenticate', return_value='synthetic-token'), \
                    patch.object(r, 'validate_inspection', return_value=receipt), \
                    patch.object(r, 'rustore', side_effect=[page, source, target]) as api:
                with self.assertRaises(r.PublishError):
                    r.publish_draft(args, {})
                self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_upload_commit_once_with_intent_journals_and_no_delete_create_or_edit(self):
        for failure in (None, 'upload', 'uploaded_listing', 'uploaded_media', 'uploaded_version',
                        'commit', 'readback', 'not_committed', 'committed_version', 'committed_listing'):
            args, page, source, target = self.fixture()
            after = dict(copy.deepcopy(target), packages=[dict(verCode=8, verName='2.0.2')])
            committed = dict(copy.deepcopy(after), versionStatus='TAKEN_FOR_MODERATION')
            if failure == 'uploaded_listing':
                after['developerContacts'] = {'email': 'changed'}
            elif failure == 'uploaded_media':
                after['fileUids'][0]['orientation'] = 'PORTRAIT'
            elif failure == 'uploaded_version':
                after['packages'][0]['verCode'] = 7
            elif failure == 'not_committed':
                committed['versionStatus'] = 'DRAFT'
            elif failure == 'committed_version':
                committed['packages'][0]['verCode'] = 7
            elif failure == 'committed_listing':
                committed['fullDescription'] = 'changed'
            responses = iter([page, source, target,
                              r.PublishError('Unknown upload') if failure == 'upload' else None, after,
                              r.PublishError('Unknown commit') if failure == 'commit' else None,
                              r.PublishError('Unknown readback') if failure == 'readback' else committed])
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, \
                    patch.object(r, 'authenticate', return_value='synthetic-token'):
                args.evidence = Path(directory) / 'evidence.json'
                args.inspection = Path(directory) / 'inspection.json'
                args.inspection.write_text(json.dumps(self.receipt(args, source)))
                evidence = {'apk_sha256': 'f' * 64}
                def respond(url, token, method='GET', **kwargs):
                    if method != 'GET':
                        phase = json.loads(args.evidence.read_text())['status']
                        self.assertEqual(phase, 'APK_UPLOAD_RESULT_UNKNOWN' if '/apk?' in url else 'COMMIT_RESULT_UNKNOWN')
                    response = next(responses)
                    if isinstance(response, Exception):
                        raise response
                    return response
                with patch.object(r, 'rustore', side_effect=respond) as api:
                    if failure:
                        with self.assertRaises(r.PublishError):
                            r.publish_draft(args, evidence)
                    else:
                        r.publish_draft(args, evidence)
                        self.assertEqual(evidence['rustore_version_status'], 'TAKEN_FOR_MODERATION')
                        self.assertEqual(evidence['status'], 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION')
                methods = [call.args[2] if len(call.args) > 2 else 'GET' for call in api.call_args_list]
                self.assertEqual(methods, ['GET', 'GET', 'GET', 'POST', 'GET', 'POST', 'GET'][:len(methods)])
                self.assertEqual(methods.count('POST'), 1 if failure in
                                 ('upload', 'uploaded_listing', 'uploaded_media', 'uploaded_version') else 2)
                self.assertNotIn('synthetic-private', args.evidence.read_text())

    def test_media_ignores_binary_and_signed_urls_but_preserves_exact_paths_and_order(self):
        _, _, source, target = self.fixture()
        self.assertEqual(r.media_snapshot(source), r.media_snapshot(target))
        changed = copy.deepcopy(source)
        changed['fileUids'].reverse()
        for item in changed['fileUids']:
            item['fileUrl'] = 'different-private'
            item['blurhash'] = 'different'
        self.assertEqual(r.media_snapshot(changed), r.media_snapshot(target))
        for case in ('icon', 'unknown_path', 'duplicate'):
            changed = copy.deepcopy(target)
            if case == 'icon':
                changed['iconUid'] = 'other/' + r.ICON_UID + '.png'
            elif case == 'unknown_path':
                changed['fileUids'][0]['fileUid'] = 'other/' + sorted(r.SCREEN_UIDS)[0] + '.png'
            else:
                changed['fileUids'].append(changed['fileUids'][0])
            with self.subTest(case=case), self.assertRaises(r.PublishError):
                r.media_snapshot(changed)

    def test_optional_null_matches_absence_but_missing_privacy_does_not(self):
        _, _, source, target = self.fixture()
        source['faq'] = None
        self.assertEqual(r.listing_hash(source), r.listing_hash(target))
        del target['privacyExplanation']
        self.assertNotEqual(r.listing_hash(source), r.listing_hash(target))

    def test_transport_blocks_every_destructive_write_or_wrong_target_before_network(self):
        for url, method in ((r.APP + '/version', 'POST'), (r.APP + '/version/' + str(r.TEMPLATE_ID), 'DELETE'),
                            (r.DETAIL + '/version/' + str(r.REPLACEMENT_ID), 'PATCH'),
                            (r.APP + '/version/43/apk?servicesType=Unknown&isMainApk=true', 'POST'),
                            ('https://example.invalid/public/auth', 'POST'),
                            (r.BASE + '/v1/application/other/version', 'GET')):
            with self.subTest(url=url, method=method), patch.object(r.urllib.request, 'build_opener') as opener:
                with self.assertRaises(r.PublishError):
                    r.rustore(url, 'synthetic-token', method)
                opener.assert_not_called()

    def test_successful_commit_without_body_is_valid(self):
        with patch.object(r.urllib.request, 'build_opener') as opener:
            response = MagicMock()
            response.read.return_value = b'{"code":"OK"}'
            opener.return_value.open.return_value.__enter__.return_value = response
            self.assertIsNone(r.rustore(r.APP + '/version/' + str(r.REPLACEMENT_ID) +
                                       '/commit?priorityUpdate=0', 'synthetic-token', 'POST'))
            self.assertEqual(opener.return_value.open.call_count, 1)

    def test_http_failure_does_not_leak_or_retry(self):
        error = urllib.error.HTTPError('https://example.invalid/?secret=synthetic-secret', 403,
                                      'synthetic-secret', {}, None)
        with patch.object(r.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(r.PublishError) as caught:
                r.rustore(r.BASE + '/auth', 'synthetic-secret', 'POST', {})
            self.assertNotIn('synthetic-secret', str(caught.exception))
            self.assertEqual(opener.return_value.open.call_count, 1)

    def test_auth_uses_approved_rsa_key_and_missing_body_blocks(self):
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
        with patch.dict(os.environ, RUSTORE_API_KEY_ID='2351035158', RUSTORE_API_PRIVATE_KEY=private):
            with patch.object(r, 'rustore', side_effect=auth):
                self.assertEqual(r.authenticate(), 'synthetic-jwe')
            for body in (None, {}, [], {'jwe': ''}):
                with patch.object(r, 'rustore', return_value=body), self.assertRaises(r.PublishError):
                    r.authenticate()

    def test_apk_requires_exact_new_identity_both_signers_and_artifact_icon_check(self):
        args = SimpleNamespace(aapt='aapt', apksigner='apksigner', apk=Path('synthetic.apk'))
        result = lambda text, status=0: SimpleNamespace(returncode=status, stdout=text)
        outputs = [result("package: name='su.linka.pictures' versionCode='8' versionName='2.0.2'"),
                   result('Signer #1 certificate SHA-256 digest: ' + r.OLD_CERT),
                   result('Signer #1 certificate SHA-256 digest: ' + r.NEW_CERT), result('icon PASS')]
        with patch.object(r.subprocess, 'run', side_effect=outputs) as run:
            r.verify_apk(args)
            self.assertEqual(run.call_count, 4)
            self.assertIn('--apk', run.call_args.args[0])
            self.assertTrue(any(str(value).endswith('check-android-launcher.py') for value in run.call_args.args[0]))
        for case in ('old_version', 'wrong_signer', 'wrong_icon'):
            changed = list(outputs)
            if case == 'old_version':
                changed[0] = result("package: name='su.linka.pictures' versionCode='7' versionName='2.0.1'")
            elif case == 'wrong_signer':
                changed[1] = outputs[2]
            else:
                changed[-1] = result('icon FAIL', 1)
            with self.subTest(case=case), patch.object(r.subprocess, 'run', side_effect=changed):
                with self.assertRaises(r.PublishError):
                    r.verify_apk(args)

    def test_only_approved_universal_apk_without_fallback(self):
        group = lambda cert: dict(certificateSha256Hash=cert, generatedUniversalApk=dict(downloadId=cert))
        self.assertEqual(r.select_universal({'generatedApks': [group(r.OLD_CERT), group(r.NEW_CERT)]}),
                         (r.NEW_CERT, r.NEW_CERT))
        for groups in ([], [dict(certificateSha256Hash=r.NEW_CERT, generatedStandaloneApks=[{}])],
                       [group('f' * 64)], [group(r.NEW_CERT), group(r.NEW_CERT)]):
            with self.assertRaises(r.PublishError):
                r.select_universal({'generatedApks': groups})

    def test_source_rejects_other_workflow_before_other_calls(self):
        args = SimpleNamespace(google_run_id='123', source_sha='a' * 40, repository='owner/repo')
        with patch.object(r.play, 'request', return_value={'path': '.github/workflows/android.yml'}) as api:
            with self.assertRaises(r.PublishError):
                r.verify_source(args)
            api.assert_called_once()

    def test_v2_failure_has_no_endpoint_fallback(self):
        with patch.object(r, 'rustore', side_effect=r.PublishError('RuStore HTTP 403')) as api:
            with self.assertRaises(r.PublishError):
                r.version_detail('synthetic-token', r.TEMPLATE_ID)
            api.assert_called_once()

    def test_cli_preserves_source_receipt_apk_order_and_prepare_is_offline(self):
        for mode in ('--inspect', '--publish-draft', '--prepare'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                receipt = Path(directory) / 'receipt.json'
                receipt.write_text('{}')
                evidence = Path(directory) / 'evidence.json'
                argv = ['publish-rustore.py', mode, '--draft-id', str(r.REPLACEMENT_ID),
                        '--google-run-id', '123', '--source-sha', 'a' * 40, '--repository', 'owner/repo',
                        '--receipt', str(receipt), '--evidence', str(evidence)]
                order = []
                def mark(name):
                    return lambda *args: order.append(name) or {}
                with patch.object(r.sys, 'argv', argv), patch('builtins.print'), \
                        patch.object(r, 'verify_source', side_effect=mark('source')), \
                        patch.object(r, 'validate_receipt', side_effect=mark('receipt')), \
                        patch.object(r, 'download_apk', side_effect=mark('apk')), \
                        patch.object(r, 'inspect', side_effect=mark('inspect')), \
                        patch.object(r, 'publish_draft', side_effect=mark('draft')):
                    self.assertEqual(r.main(), 0)
                self.assertEqual(order, [] if mode == '--prepare' else
                                 ['source', 'receipt', 'apk', 'inspect' if mode == '--inspect' else 'draft'])

    def test_old_mutating_cli_modes_are_removed_before_any_service(self):
        for mode in ('--replace-template', '--prepare-clone', '--publish'):
            argv = ['publish-rustore.py', mode, '--google-run-id', '123',
                    '--source-sha', 'a' * 40, '--repository', 'owner/repo']
            with self.subTest(mode=mode), patch.object(r.sys, 'argv', argv), \
                    patch.object(r.sys, 'stderr'), patch.object(r, 'authenticate') as auth:
                with self.assertRaises(SystemExit):
                    r.main()
                auth.assert_not_called()


if __name__ == '__main__':
    unittest.main()
