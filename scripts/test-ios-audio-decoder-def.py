#!/usr/bin/env python3
"""The cinterop def is consumed by ld, not clang; preserve only the known legacy upgrade."""
from pathlib import Path
import os
import runpy
import subprocess
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import Mock, patch

module = runpy.run_path(str(Path(__file__).with_name('ios_audio_decoder_outputs.py')))
prefix = Path('/generated/build/decoder/iosSimulatorArm64')
definition = module['definition'](prefix)
assert '-pthread' not in definition
assert 'staticLibraries = liblinka_audio_decoder.a\n' in definition
assert '-framework LinkaFFmpeg' in definition and '-framework AudioToolbox' in definition
assert 'libavcodec.a' not in definition and 'libavformat.a' not in definition
assert module['current_definition'](definition, prefix)
legacy = definition.replace('staticLibraries = liblinka_audio_decoder.a\n',
    'staticLibraries = liblinka_audio_decoder.a libavformat.a libavcodec.a libswresample.a libavutil.a\n')
legacy = legacy.replace(f'-F{prefix} -framework LinkaFFmpeg ', '').replace(' -framework AudioToolbox', '')
assert not module['current_definition'](legacy, prefix)
assert not module['current_definition'](legacy.replace('linkerOpts = -lm ', 'linkerOpts = -lm -pthread '), prefix)
for altered in (definition + 'linkerOpts = -foreign\n', definition.replace(str(prefix), '/foreign/build/path')):
    try:
        module['current_definition'](altered, prefix)
    except RuntimeError:
        pass
    else:
        raise AssertionError('Unknown output definition was accepted')
print('decoder def checks PASS')

with patch.object(subprocess, 'run', side_effect=AssertionError('import executed a tool')):
    builder = runpy.run_path(str(Path(__file__).with_name('build-ios-audio-decoder.py')))
preflight = builder['PREFLIGHT']
with patch.dict(os.environ, {'DEVELOPER_DIR': '/selected/Xcode/Contents/Developer'}, clear=True), \
        patch.object(subprocess, 'run', side_effect=AssertionError('explicit Xcode was ignored')):
    assert preflight['developer_environment']()['DEVELOPER_DIR'] == '/selected/Xcode/Contents/Developer'
with patch.dict(os.environ, {}, clear=True), \
        patch.object(subprocess, 'run', return_value=SimpleNamespace(stdout='/runner/Xcode/Contents/Developer\n')) as selected:
    assert preflight['developer_environment']()['DEVELOPER_DIR'] == '/runner/Xcode/Contents/Developer'
    assert selected.call_args.args[0] == ['xcode-select', '-p']
with patch.dict(os.environ, {}, clear=True), \
        patch.object(preflight['tempfile'], 'gettempdir', return_value='/runner/tmp'):
    assert preflight['work_root'](Path('/runner/project')) == Path('/runner/tmp/linka-ios-ffmpeg-preflight')
    assert preflight['work_root'](Path('/Volumes/data/linka/project')) == Path('/Volumes/data/linka/project/build/ios-ffmpeg-preflight')
with patch.dict(os.environ, {'LINKA_FFMPEG_WORK_ROOT': '/runner/work/ffmpeg'}, clear=True):
    assert preflight['work_root'](Path('/Volumes/data/linka/project')) == Path('/runner/work/ffmpeg')

def fake_xcode(root):
    developer = root.resolve() / 'Xcode.app/Contents/Developer'
    binary = developer / 'Toolchains/XcodeDefault.xctoolchain/usr/bin'
    binary.mkdir(parents=True)
    for name in ('clang', 'ar', 'ranlib', 'nm', 'lipo', 'otool'):
        (binary / name).write_text('synthetic executable, never run')
        (binary / name).chmod(0o700)
    for sdk in ('iphoneos', 'iphonesimulator'):
        (developer / sdk).mkdir()
    return {'DEVELOPER_DIR': str(developer)}, binary

with tempfile.TemporaryDirectory() as directory:
    env, binary = fake_xcode(Path(directory))
    def fake_command(args, selected_env):
        assert selected_env == env
        if args == ['xcodebuild', '-version']:
            return xcode
        if args[0] == 'xcrun':
            assert args[3] in ('--show-sdk-path', '--show-sdk-version'), 'unexpected tool lookup'
            return str(Path(env['DEVELOPER_DIR']) / args[2]) if args[3] == '--show-sdk-path' else '26.5\n'
        assert args == [str(binary / 'clang'), '--version']
        return 'Apple clang version 21\n'
    with patch.dict(module['toolchain'].__globals__, command=fake_command):
        for xcode in ('Xcode 26.0\nBuild version 17A1\n', 'Xcode 27.2\nBuild version 18C1\n'):
            chain = module['toolchain'](env)
            assert chain['xcode'] == xcode
            assert set(chain) == {'xcode', 'developer_dir', 'iphoneos', 'iphonesimulator'}
            assert chain['iphoneos']['clang'] == chain['iphonesimulator']['clang'] == str(binary / 'clang')
        previous = chain
        xcode = 'Xcode 27.2\nBuild version 18C2\n'
        assert module['toolchain'](env) != previous, 'Xcode build change must invalidate the cache'
        for xcode in ('Xcode 25.4\nBuild version old\n', 'unknown'):
            try:
                module['toolchain'](env)
            except RuntimeError:
                pass
            else:
                raise AssertionError('unsupported toolchain accepted')
        xcode = 'Xcode 26.6\nBuild version 17F113\n'
        with patch.dict(module['toolchain'].__globals__, command=lambda args, selected_env:
                        directory if '--show-sdk-path' in args else fake_command(args, selected_env)):
            try:
                module['toolchain'](env)
            except RuntimeError:
                pass
            else:
                raise AssertionError('SDK outside selected Xcode accepted')
    with patch.object(subprocess, 'run', side_effect=AssertionError('tool lookup launched a subprocess')):
        for name in ('clang', 'ar', 'ranlib', 'nm', 'lipo', 'otool'):
            assert module['selected_tool'](env, name) == str(binary / name)
        for invalid_env, name in ((dict(env, TOOLCHAINS='swift-custom'), 'ar'), (env, '../ar')):
            try:
                module['selected_tool'](invalid_env, name)
            except RuntimeError:
                pass
            else:
                raise AssertionError('unsupported tool selection accepted')
        (binary / 'ar').chmod(0o600)
        try:
            module['selected_tool'](env, 'ar')
        except RuntimeError:
            pass
        else:
            raise AssertionError('non-executable tool accepted')
        (binary / 'ar').unlink()
        outside = Path(directory) / 'outside'
        outside.write_text('foreign executable')
        outside.chmod(0o700)
        (binary / 'ar').symlink_to(outside)
        try:
            module['selected_tool'](env, 'ar')
        except RuntimeError:
            pass
        else:
            raise AssertionError('tool symlink escaped selected Xcode')

# Execute the real default build orchestration, trapping every proof/fixture entry point.
def forbidden(*args, **kwargs):
    raise AssertionError('build-only path touched fixtures, proof, or unmocked external tools')

task = SimpleNamespace(root=Path('/runner/build/owned'), env={}, originals={}, result={}, save=Mock())
def source(task):
    task.result['downloads'] = [{'url': preflight['SOURCE'], 'sha256': builder['SOURCE_SHA']}]

build_target = Mock(return_value=(Path('/runner/build/prefix'), Path('/runner/build/test')))
with patch.dict(builder['main'].__globals__, Preflight=lambda: task, vendored_source=source,
                target=build_target, bridge_fixtures=forbidden, proof=forbidden, scoped_kotlin=forbidden), \
        patch.dict(builder['OUTPUTS'], current_sources=Mock(), toolchain=lambda env: previous), \
        patch.object(subprocess, 'run', side_effect=forbidden), \
        patch.object(sys, 'argv', ['producer']):
    builder['main']()
assert task.result['status'] == 'BUILD_ONLY' and task.result['toolchain'] == previous
assert task.result['original_fixture_files_verified'] == 0
assert [call.args[1] for call in build_target.call_args_list] == ['iphonesimulator', 'iphoneos']
print('decoder producer portability/build-only checks PASS')

# Selected SDK-neutral tools require no xcrun subprocess, even across both SDKs.
with tempfile.TemporaryDirectory() as directory:
    task = preflight['Preflight'].__new__(preflight['Preflight'])
    task.root = Path(directory)
    (task.root / 'logs').mkdir()
    task.env, binary = fake_xcode(task.root)
    task.result = {'commands': []}
    task.started = preflight['time'].monotonic()
    process = Mock(pid=12345)
    process.wait.return_value = 0
    archive = task.root / 'library.a'
    archive.write_bytes(b'owned synthetic archive')
    for sdk, platform in (('iphonesimulator', '7'), ('iphoneos', '2')):
        task.result['toolchain'] = dict(developer_dir=task.env['DEVELOPER_DIR'],
                                       **{sdk: dict(clang=str(binary / 'clang'))})
        with patch.object(subprocess, 'Popen', side_effect=AssertionError('redundant xcrun lookup')):
            tools = task.resolve_tools(sdk, ('ar', 'lipo', 'otool'))
            task.result['toolchain'][sdk]['clang'] = '/foreign/Xcode/clang'
            try:
                task.resolve_tools(sdk, ('ar',))
            except RuntimeError:
                pass
            else:
                raise AssertionError('selected clang/receipt mismatch accepted')
            task.result['toolchain'][sdk]['clang'] = str(binary / 'clang')
        assert task.result['inspection_toolchains'][sdk] == dict(developer_dir=task.env['DEVELOPER_DIR'], tools=tools)
        def inspect(args, **kwargs):
            assert args[0] in tools.values() and kwargs['env'] == task.env
            text = {'lipo': 'arm64', 'otool': f'cmd LC_BUILD_VERSION\ncmdsize 24\nplatform {platform}\nminos 17.0', 'ar': 'one.o\n'}
            kwargs['stdout'].write(text[Path(args[0]).name].encode())
            return process
        with patch.object(subprocess, 'Popen', side_effect=inspect) as commands:
            assert task.macho(archive, {platform}, tools)['build_commands'] == 1
        assert [c.args[0][0] for c in commands.call_args_list] == [tools['lipo'], tools['otool'], tools['ar']]
        def published_inspect(args, env):
            assert args[0] in tools.values() and env == task.env
            return {'lipo': 'arm64', 'otool': f'cmd LC_BUILD_VERSION\ncmdsize 24\nplatform {platform}\nminos 17.0', 'ar': 'one.o\n'}[Path(args[0]).name]
        with patch.dict(module['native_metadata'].__globals__, command=published_inspect):
            module['native_metadata'](archive, platform, task.env)
        task.source = task.root / 'source'
        task.result['toolchain'][sdk]['path'] = str(Path(task.env['DEVELOPER_DIR']) / sdk)
        def configure_only(args, **kwargs):
            assert args[0] == task.source / 'configure', 'target repeated xcrun SDK/tool lookup'
            assert f"--sysroot={task.result['toolchain'][sdk]['path']}" in args
            for name in ('clang', 'ar', 'ranlib', 'nm'):
                option = 'cc' if name == 'clang' else name
                assert f'--{option}={binary / name}' in args
            raise RuntimeError('offline configure boundary reached')
        with patch.object(task, 'run', side_effect=configure_only), \
                patch.object(subprocess, 'Popen', side_effect=AssertionError('native tool was executed')):
            try:
                builder['target'](task, sdk, 'arm64-apple-ios17.0' + ('-simulator' if platform == '7' else ''))
            except RuntimeError as error:
                assert str(error) == 'offline configure boundary reached'
            else:
                raise AssertionError('expected offline configure boundary')
        runtime_prefix = task.root / f'runtime-{sdk}'
        runtime_prefix.mkdir()
        runtime_commands = []
        def runtime_command(args, env):
            runtime_commands.append(args)
            assert args[0] in [str(binary / name) for name in ('clang', 'nm', 'lipo', 'otool')]
            assert env == task.env
            if args[0] == str(binary / 'clang'):
                assert args[args.index('-isysroot') + 1] == task.result['toolchain'][sdk]['path']
                assert args[args.index('-target') + 1] == 'arm64-apple-ios17.0' + ('-simulator' if platform == '7' else '')
                return ''
            if args[0] == str(binary / 'nm'):
                return '_fstat'
            if args[0] == str(binary / 'lipo'):
                return 'arm64'
            if args[1] == '-hv':
                return 'DYLIB'
            return (f'cmd LC_BUILD_VERSION\ncmdsize 24\nplatform {platform}\nminos 17.0\n'
                    f"cmd LC_ID_DYLIB\ncmdsize 80\nname {module['INSTALL_NAME']} \n"
                    'current version 8.1.2\ncompatibility version 8.1.2\n')
        with patch.dict(module['build_runtime'].__globals__, command=runtime_command), \
                patch.object(subprocess, 'run', side_effect=AssertionError('native runtime command executed')):
            module['build_runtime'](runtime_prefix, sdk, task.env,
                                    Path(__file__).resolve().parents[1] / 'third_party/ffmpeg/ffmpeg-8.1.2.tar.xz',
                                    task.result['toolchain'][sdk])
        assert [Path(args[0]).name for args in runtime_commands] == ['clang', 'nm', 'lipo', 'otool', 'otool', 'nm']

    # Deadline evidence must precede kill; a diagnostic failure must not replace the deadline.
    events = []
    process.wait.side_effect = [subprocess.TimeoutExpired(['owned-tool'], 30), 0]
    def diagnostic(args, **kwargs):
        events.append('diagnostic')
        assert str(process.pid) in args and kwargs['timeout'] == 3
        assert kwargs['stdin'] == subprocess.DEVNULL and kwargs['env'] == task.env
        assert 'preexec_fn' in kwargs and 'capture_output' not in kwargs
        if args[0] == '/usr/bin/sample':
            assert args[-2:] == ['-file', '/dev/stdout'], 'sample report must reach the owned capped artifact'
        with patch.object(preflight['resource'], 'setrlimit') as cap:
            kwargs['preexec_fn']()
            cap.assert_called_once_with(preflight['resource'].RLIMIT_FSIZE, (16 * 1024 * 1024, 16 * 1024 * 1024))
        raise PermissionError('native sampling denied')
    with patch.object(subprocess, 'Popen', return_value=process), \
            patch.object(subprocess, 'run', side_effect=diagnostic), \
            patch.object(os, 'killpg', side_effect=lambda pid, sig: events.append(('kill', pid, sig))):
        try:
            task.run(['owned-tool'])
        except RuntimeError as error:
            assert str(error).startswith('process deadline:')
        else:
            raise AssertionError('timeout was hidden')
    assert events == ['diagnostic', 'diagnostic', ('kill', process.pid, preflight['signal'].SIGKILL)]
    assert task.result['commands'][-1]['timed_out']
    assert task.result['commands'][-1]['timeout'] == 30
    assert task.result['commands'][-1]['diagnostic_errors'] == ['PermissionError', 'PermissionError']
    assert (task.root / 'result.json').is_file()
print('decoder selected-tool inspection/deadline diagnostics checks PASS')
