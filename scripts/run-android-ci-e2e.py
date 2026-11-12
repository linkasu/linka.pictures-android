#!/usr/bin/env python3
"""Run exact prebuilt APK tests sequentially on the disposable CI emulator."""
import os
from pathlib import Path
import re
import subprocess
import sys


def verify_result(text, count):
    assert re.search(rf'^OK \({count} tests?\)\s*$', text, re.M), 'Missing exact passing test count'
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=', text), 'Instrumentation failed'
    assert 'INSTRUMENTATION_CODE: -1' in text, 'Instrumentation did not finish normally'
    completed = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', text, re.M)
    assert completed.count('0') == count and set(completed) <= {'0', '1'}, 'Failed, skipped or missing test result'


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'CI-owned emulator only'
    serial = 'emulator-5554'
    output = Path('artifacts/e2e')
    output.mkdir(parents=True, exist_ok=True)
    def adb(*args, timeout=120):
        return subprocess.check_output(['adb', '-s', serial, *args], text=True, stderr=subprocess.STDOUT, timeout=timeout)
    devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
    assert [line.split()[0] for line in devices if line.endswith('\tdevice')] == [serial], 'Require exactly the owned emulator'
    tests = [
        ('startup', 'su.linka.pictures.activity.DevelopmentStartupTest', 'su.linka.pictures.development.test/androidx.test.runner.AndroidJUnitRunner', 1),
        ('message-layout', 'su.linka.pictures.activity.MessageRowLayoutTest', 'su.linka.pictures.development.test/androidx.test.runner.AndroidJUnitRunner', 1),
        ('admission', 'su.linka.pictures.bootstrap.AndroidLibraryAdmissionTest', 'su.linka.pictures.shared.test/su.linka.pictures.bootstrap.LibraryTestRunner', 7),
    ]
    try:
        for apk in ('debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk', 'shared/shared-debug-androidTest.apk'):
            adb('install', '-r', str(Path('artifacts/apks') / apk))
        for name, test, runner, count in tests:
            text = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test,
                       '-e', 'expected_page_size', '4096', runner, timeout=600)
            (output / f'{name}.txt').write_text(text)
            print(text, flush=True)
            verify_result(text, count)
    finally:
        # Collect before teardown; never turn a failed test into a passing exit code.
        for name, args in [('logcat.txt', ('logcat', '-d')), ('window.txt', ('shell', 'dumpsys', 'window'))]:
            try:
                (output / name).write_text(adb(*args, timeout=30))
            except (subprocess.SubprocessError, OSError):
                print(f'Diagnostic collection failed: {name}', file=sys.stderr)
        for args in [('shell', 'screencap', '-p', '/sdcard/message-row-ci.png'),
                     ('pull', '/sdcard/message-row-ci.png', str(output / 'screen.png')),
                     ('pull', '/sdcard/Android/data/su.linka.pictures.development/files/message-row-layout/', str(output))]:
            try:
                adb(*args, timeout=30)
            except (subprocess.SubprocessError, OSError):
                print('Optional screenshot collection unavailable', file=sys.stderr)


if __name__ == '__main__':
    if '--self-test' in sys.argv:
        verify_result('INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1', 1)
        verify_result('INSTRUMENTATION_STATUS_CODE: 0\n' * 7 + 'OK (7 tests)\nINSTRUMENTATION_CODE: -1', 7)
        for bad in ('OK (0 tests)\nINSTRUMENTATION_CODE: -1', 'FAILURES!!!',
                    'OK (1 test)\nINSTRUMENTATION_FAILED', 'OK (1 test)\nINSTRUMENTATION_CODE: 0',
                    'INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\nINSTRUMENTATION_CODE: -1'):
            try:
                verify_result(bad, 1)
            except AssertionError:
                pass
            else:
                raise AssertionError('Accepted failed/empty instrumentation')
        print('Instrumentation result checks PASS')
    else:
        main()
