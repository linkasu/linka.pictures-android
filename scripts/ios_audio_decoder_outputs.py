"""Verified generated-prefix publication. Never consumes/truncates an unknown directory."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tarfile
import uuid

MANIFEST = 'linka-audio-decoder-manifest.json'
FORMAT = 'su.linka.development.ios-audio-decoder'
TARGETS = {'iosSimulatorArm64': ('iphonesimulator', '7'), 'iosArm64': ('iphoneos', '2')}
RUNTIME = 'LinkaFFmpeg.framework'
INSTALL_NAME = '@rpath/LinkaFFmpeg.framework/LinkaFFmpeg'
SOURCE_NOTICE = '''This framework uses FFmpeg 8.1.2 under GNU LGPL version 2.1 or later.
Original upstream notices: COPYING.LGPLv2.1 and LICENSE.md in this framework.
Base source archive: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz
Base archive SHA256: 464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c
Complete base source is also supplied in third_party/ffmpeg/ffmpeg-8.1.2.tar.xz.
Build/replacement recipe: docs/development/ios-ffmpeg-runtime.md in the source distribution.
The archive URL identifies the upstream baseline, not any recipient modifications.
No application license, patent clearance or shipping approval is granted by this notice.
'''
FFMPEG_LIBRARIES = ('libavformat.a', 'libavcodec.a', 'libswresample.a', 'libavutil.a')
VENDORED_PINS = {
    'ffmpeg-8.1.2.tar.xz': '464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c',
    'ffmpeg-8.1.2.tar.xz.asc': '0a0963fccd70597838073f3e31b20f4a4d8cc2b5e577472c9a5a1f22624246f8',
    'ffmpeg-release-key.asc': '397b3becedcd5a98769967ff1ff8501ddc89f8368b8f766e4701377d7dbaabe5',
    'ffmpeg-release-key.gpg': 'fd543a75d3bba61b759ca25b135e6f91b34aa37ae6987316e5c636bf3c78ae8d',
    'COPYING.LGPLv2.1': '246041b6ecf9bc32d718a62c57877c78b5eb397b6467e74ed7ae2626ab189c30',
    'LICENSE.md': '2e1d16c72fd74e12063776371da757322f8b77589386532f4fd8634bde7de1af',
}


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(path):
    with path.open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def command(args, env):
    result = subprocess.run(list(map(str, args)), env=env, stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=30, check=True)
    require(len(result.stdout) + len(result.stderr) <= 16 * 1024 * 1024, 'inspection log cap')
    return result.stdout.decode()


def developer_environment(env=None):
    env = dict(os.environ if env is None else env)
    require(env.get('TOOLCHAINS', '') in ('', 'XcodeDefault', 'com.apple.dt.toolchain.XcodeDefault'),
            'nondefault TOOLCHAINS is not supported')
    if not env.get('DEVELOPER_DIR'):
        env['DEVELOPER_DIR'] = subprocess.run(['xcode-select', '-p'], env=env,
            capture_output=True, text=True, check=True, timeout=10).stdout.strip()
    require(env['DEVELOPER_DIR'], 'selected Xcode developer directory is empty')
    require(Path(env['DEVELOPER_DIR']).is_absolute(), 'absolute selected Xcode developer directory required')
    return env


def selected_tool(env, name):
    require(name in ('clang', 'ar', 'ranlib', 'nm', 'lipo', 'otool'), 'unsupported Xcode tool')
    env = developer_environment(env)
    developer = Path(env['DEVELOPER_DIR']).resolve(strict=True)
    directory = developer / 'Toolchains/XcodeDefault.xctoolchain/usr/bin'
    tool = directory / name
    require(directory.resolve(strict=True).is_relative_to(developer) and
            tool.resolve(strict=True).is_relative_to(developer) and tool.is_file() and os.access(tool, os.X_OK),
            f'selected tool is missing, not executable or outside selected Xcode: {name}')
    return str(tool)


def toolchain(env):
    env = developer_environment(env)
    clang = selected_tool(env, 'clang')
    result = {'xcode': command(['xcodebuild', '-version'], env), 'developer_dir': str(Path(env['DEVELOPER_DIR']).resolve(strict=True))}
    version = re.match(r'Xcode (\d+)(?:\.\d+)*\n', result['xcode'])
    require(version and int(version[1]) >= 26, 'Xcode 26 or newer required')
    for sdk, _ in TARGETS.values():
        result[sdk] = {key: command(['xcrun', '--sdk', sdk, option], env).strip()
                       for key, option in [('path', '--show-sdk-path'), ('version', '--show-sdk-version')]}
        require(Path(result[sdk]['path']).resolve(strict=True).is_relative_to(Path(result['developer_dir'])),
                'SDK is outside selected Xcode')
        result[sdk]['clang'] = clang
        result[sdk]['clang_version'] = command([result[sdk]['clang'], '--version'], env)
    return result


def definition(prefix):
    return f'''headers = linka_audio_decoder.h
headerFilter = linka_audio_decoder.h
package = su.linka.pictures.platform.audio.decoder
compilerOpts = -I{prefix}/include
staticLibraries = liblinka_audio_decoder.a
libraryPaths = {prefix}/lib
linkerOpts = -F{prefix} -framework LinkaFFmpeg -lm -framework CoreFoundation -framework CoreVideo -framework CoreMedia -framework AudioToolbox
'''


def current_definition(actual, prefix):
    expected = definition(prefix)
    # Only the exact historical static definitions may transition to schema2.
    legacy = expected.replace('staticLibraries = liblinka_audio_decoder.a\n',
        'staticLibraries = liblinka_audio_decoder.a libavformat.a libavcodec.a libswresample.a libavutil.a\n')
    legacy = legacy.replace(f'-F{prefix} -framework LinkaFFmpeg ', '').replace(' -framework AudioToolbox', '')
    require(actual in (expected, legacy, legacy.replace('linkerOpts = -lm ', 'linkerOpts = -lm -pthread ')), 'published def path/package mismatch')
    return actual == expected


def runtime_privacy(imports):
    # Apple's listed BootTime APIs exclude clock_gettime/gettimeofday. Do not invent DiskSpace use.
    require(not imports.intersection({'_statfs', '_fstatfs', '_statvfs', '_fstatvfs'}), 'New DiskSpace API requires privacy review')
    reasons = []
    if imports.intersection({'_stat', '_fstat', '_lstat', '_fstatat'}):
        reasons.append(dict(NSPrivacyAccessedAPIType='NSPrivacyAccessedAPICategoryFileTimestamp',
                            NSPrivacyAccessedAPITypeReasons=['C617.1']))
    if '_mach_absolute_time' in imports:
        reasons.append(dict(NSPrivacyAccessedAPIType='NSPrivacyAccessedAPICategorySystemBootTime',
                            NSPrivacyAccessedAPITypeReasons=['35F9.1']))
    return dict(NSPrivacyAccessedAPITypes=reasons)


def verify_runtime(prefix, sdk, env):
    framework = prefix / RUNTIME
    binary = framework / 'LinkaFFmpeg'
    require(command([selected_tool(env, 'lipo'), '-archs', binary], env).strip() == 'arm64', 'runtime architecture')
    header = command([selected_tool(env, 'otool'), '-hv', binary], env)
    require('DYLIB' in header, 'runtime is not a dynamic library')
    load = command([selected_tool(env, 'otool'), '-l', binary], env)
    expected = '2' if sdk == 'iphoneos' else '7'
    builds = re.findall(r'cmd LC_BUILD_VERSION\s+cmdsize \d+\s+platform (\S+)\s+minos (\S+)', load)
    require(len(builds) == 1 and builds[0][0] in (expected, 'IOS' if expected == '2' else 'IOSSIMULATOR') and
            builds[0][1] in ('17.0', '17.0.0'), 'runtime platform/minimum')
    require(re.search(r'cmd LC_ID_DYLIB\s+cmdsize \d+\s+name ' + re.escape(INSTALL_NAME) + r' ', load), 'runtime install name')
    require('current version 8.1.2' in load and 'compatibility version 8.1.2' in load, 'runtime ABI/version metadata')
    require('LC_CODE_SIGNATURE' not in load, 'producer must not sign runtime')
    imports = set(re.findall(r'\b_\w+', command([selected_tool(env, 'nm'), '-u', binary], env)))
    privacy = plistlib.loads((framework / 'PrivacyInfo.xcprivacy').read_bytes())
    require(privacy == runtime_privacy(imports), 'runtime privacy/import mismatch')
    require((framework / 'Source.txt').read_text() == SOURCE_NOTICE, 'runtime source notice')
    info = plistlib.loads((framework / 'Info.plist').read_bytes())
    require(info['CFBundleExecutable'] == 'LinkaFFmpeg' and info['CFBundlePackageType'] == 'FMWK' and
            info['CFBundleIdentifier'] == 'su.linka.pictures.ffmpeg' and
            info['CFBundleShortVersionString'] == '8.1.2' and info['CFBundleVersion'] == '8.1.2' and info['MinimumOSVersion'] == '17.0' and
            info['CFBundleSupportedPlatforms'] == ['iPhoneOS' if sdk == 'iphoneos' else 'iPhoneSimulator'], 'runtime bundle metadata')


def build_runtime(prefix, sdk, env, archive, sdk_record=None):
    """Link only the four verified FFmpeg libraries, never the application bridge."""
    require(archive.is_file() and not archive.is_symlink() and digest(archive) == VENDORED_PINS['ffmpeg-8.1.2.tar.xz'], 'runtime base source/notices provenance')
    framework = prefix / RUNTIME
    framework.mkdir()
    triple = 'arm64-apple-ios17.0' + ('-simulator' if sdk == 'iphonesimulator' else '')
    env = developer_environment(env)
    sysroot = sdk_record['path'] if sdk_record else command(['xcrun', '--sdk', sdk, '--show-sdk-path'], env).strip()
    clang = selected_tool(env, 'clang')
    require(not sdk_record or sdk_record['clang'] == clang, 'runtime selected clang/receipt mismatch')
    args = [clang, '-target', triple, '-isysroot', sysroot, '-dynamiclib',
            '-Wl,-install_name,' + INSTALL_NAME, '-Wl,-no_adhoc_codesign', '-Wl,-current_version,8.1.2', '-Wl,-compatibility_version,8.1.2']
    for name in FFMPEG_LIBRARIES:
        args += ['-Xlinker', '-force_load', '-Xlinker', str(prefix / 'lib' / name)]
    args += ['-lm', '-framework', 'CoreFoundation', '-framework', 'CoreVideo', '-framework', 'CoreMedia',
             '-o', str(framework / 'LinkaFFmpeg')]
    command(args, env)
    imports = set(re.findall(r'\b_\w+', command([selected_tool(env, 'nm'), '-u', framework / 'LinkaFFmpeg'], env)))
    info = dict(CFBundleExecutable='LinkaFFmpeg', CFBundleIdentifier='su.linka.pictures.ffmpeg',
                CFBundleName='LinkaFFmpeg', CFBundlePackageType='FMWK', CFBundleVersion='8.1.2',
                CFBundleShortVersionString='8.1.2', MinimumOSVersion='17.0',
                CFBundleSupportedPlatforms=['iPhoneOS' if sdk == 'iphoneos' else 'iPhoneSimulator'])
    (framework / 'Info.plist').write_bytes(plistlib.dumps(info))
    (framework / 'PrivacyInfo.xcprivacy').write_bytes(plistlib.dumps(runtime_privacy(imports)))
    (framework / 'Source.txt').write_text(SOURCE_NOTICE)
    with tarfile.open(archive) as source:
        for name in ('COPYING.LGPLv2.1', 'LICENSE.md'):
            (framework / name).write_bytes(source.extractfile('ffmpeg-8.1.2/' + name).read())
    verify_runtime(prefix, sdk, env)


def rebuild_compatible_runtime(source, destination, sdk, flags, archive):
    """Recipient build, isolated from production: no production manifest or verification bypass."""
    source = source.resolve(strict=True)
    destination = output_root(str(destination))
    require(source.is_dir() and (source / 'configure').is_file() and not destination.exists() and
            not destination.is_relative_to(source), 'fresh separate runtime build required')
    destination.mkdir(parents=True)
    build = destination / 'compile'
    build.mkdir()
    prefix = destination / 'runtime'
    env = developer_environment()
    sysroot = command(['xcrun', '--sdk', sdk, '--show-sdk-path'], env).strip()
    tools = {name: selected_tool(env, name) for name in ('clang', 'ar', 'ranlib', 'nm')}
    triple = 'arm64-apple-ios17.0' + ('-simulator' if sdk == 'iphonesimulator' else '')
    for key in ('CFLAGS', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS', 'LIBRARY_PATH', 'CPATH', 'PKG_CONFIG_PATH'):
        env.pop(key, None)
    configure = [source / 'configure', *flags, f'--prefix={prefix}', f"--cc={tools['clang']}",
                 f"--ar={tools['ar']}", f"--ranlib={tools['ranlib']}", f"--nm={tools['nm']}", '--pkg-config=/usr/bin/false',
                 f'--sysroot={sysroot}', f'--extra-cflags=-target {triple}', f'--extra-ldflags=-target {triple}']
    subprocess.run(list(map(str, configure)), cwd=build, env=env, check=True, timeout=180)
    subprocess.run(['make', '-j4', 'install-libs', 'install-headers'], cwd=build, env=env, check=True, timeout=600)
    configuration = {}
    for path, field, checksum in [('config.h', 'config_text', 'config_sha256'),
                                  ('config_components.h', 'components_text', 'components_sha256'),
                                  ('ffbuild/config.mak', 'make_config_text', 'make_config_sha256')]:
        configuration[field] = (build / path).read_text()
        configuration[checksum] = hashlib.sha256(configuration[field].encode()).hexdigest()
    flags_valid(configuration)
    build_runtime(prefix, sdk, env, archive, dict(path=sysroot, clang=tools['clang']))
    (destination / 'recipient-build.json').write_text(json.dumps(dict(scope='recipient compatible-source rebuild; not production provenance',
        configure=list(map(str, configure)), sdk=sdk, source=str(source), files=inventory(prefix)), indent=2) + '\n')
    print(f'Unsigned replaceable runtime: {prefix / RUNTIME}; sign with your own permitted identity for device use')


def inventory(root):
    files = {}
    for path in root.rglob('*'):
        require(not path.is_symlink(), 'generated outputs must be self-contained, not symlinks')
        if path == root / MANIFEST:
            continue
        files[str(path.relative_to(root))] = 'directory' if path.is_dir() else digest(path)
    return files


def native_metadata(path, platform, env):
    require(command([selected_tool(env, 'lipo'), '-archs', path], env).strip() == 'arm64', 'published architecture')
    load = command([selected_tool(env, 'otool'), '-l', path], env)
    builds = re.findall(r'cmd LC_BUILD_VERSION\s+cmdsize \d+\s+platform (\S+)\s+minos (\S+)', load)
    members = command([selected_tool(env, 'ar'), '-t', path], env)
    require(builds and len(builds) == sum(x.endswith('.o') for x in members.splitlines()), 'missing object platform/minimum')
    require(all(p == platform and v in ('17.0', '17.0.0') for p, v in builds), 'published platform/minimum')


def flags_valid(record):
    config = record['config_text']
    for name in ('GPL', 'NONFREE', 'VERSION3', 'NETWORK', 'FFMPEG', 'AVFILTER', 'LIBVORBIS', 'LIBMP3LAME', 'LIBX264'):
        require(re.search(rf'^#define CONFIG_{name} 0$', config, re.M), f'published forbidden config {name}')
    require(re.search(r'^#define HAVE_PTHREADS 0$', config, re.M), 'published codec threading')
    expected = dict(DECODER={'VORBIS','AMRNB','PCM_S16LE','MP3'}, DEMUXER={'OGG','AMR','MOV','WAV','MP3'},
                    PROTOCOL={'FILE'}, ENCODER=set(), MUXER=set(), FILTER=set(), HWACCEL=set())
    components = record['components_text']
    for kind, values in expected.items():
        require(set(re.findall(rf'^#define CONFIG_(\w+)_{kind} 1$', components, re.M)) == values, 'published component expansion')
    for field, key in [('config_text','config_sha256'), ('components_text','components_sha256')]:
        require(hashlib.sha256(record[field].encode()).hexdigest() == record[key], 'published config hash')
    require(hashlib.sha256(record['make_config_text'].encode()).hexdigest() == record['make_config_sha256'], 'published make config hash')


def current_sources(project, flags):
    vendor = project / 'third_party/ffmpeg'
    for name, expected in VENDORED_PINS.items():
        require((vendor / name).is_file() and not (vendor / name).is_symlink() and digest(vendor / name) == expected,
                f'vendored source provenance mismatch: {name}')
    names = ['shared/src/nativeInterop/c/linka_audio_decoder.c',
              'shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h']
    require(all((project / name).is_file() and not (project / name).is_symlink() for name in names), 'audio bridge sources are incomplete')
    return {'files': {name: digest(project / name) for name in names},
            'vendor_files': {str((vendor / name).relative_to(project)): digest(vendor / name) for name in (*VENDORED_PINS, 'README.md')},
            'flags': flags, 'source_archive_sha256': '464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c',
            'source_version': '8.1.2', 'signer': 'FCF986EA15E6E293A5644F10B4322F04D67658D8'}


def output_root(argument):
    root = Path(argument)
    require(root.is_absolute() and '..' not in root.parts and 'build' in root.parts and len(root.parts) > 3, 'explicit absolute generated build directory required')
    require(not root.is_symlink(), 'output root is a symlink')
    require(all(not parent.is_symlink() for parent in root.parents), 'output ancestor is a symlink')
    return root


def verify_headers(prefix, archive):
    generated = {
        'libavutil/avconfig.h': '/* Generated by ffmpeg configure */\n#ifndef AVUTIL_AVCONFIG_H\n#define AVUTIL_AVCONFIG_H\n#define AV_HAVE_BIGENDIAN 0\n#define AV_HAVE_FAST_UNALIGNED 1\n#endif /* AVUTIL_AVCONFIG_H */\n',
        'libavutil/ffversion.h': '/* Automatically generated by version.sh, do not manually edit! */\n#ifndef AVUTIL_FFVERSION_H\n#define AVUTIL_FFVERSION_H\n#define FFMPEG_VERSION "8.1.2"\n#endif /* AVUTIL_FFVERSION_H */\n',
    }
    inventory(prefix / 'include')
    with tarfile.open(archive) as source:
        for header in (prefix / 'include').rglob('*.h'):
            name = str(header.relative_to(prefix / 'include'))
            if name == 'linka_audio_decoder.h':
                continue  # Verified against its recorded bridge fingerprint by the caller.
            expected = generated.get(name)
            if expected is None:
                member = source.getmember(f'ffmpeg-8.1.2/{name}')
                require(member.isfile() and member.size <= 1024 * 1024, 'invalid cached header provenance')
                expected = source.extractfile(member).read()
            else:
                expected = expected.encode()
            require(header.read_bytes() == expected, f'cached header changed: {name}')


def inspect(root, project, flags, env):
    """Return (owned manifest or None, exact-current fastpath). Never writes root."""
    if not root.exists() or not any(root.iterdir()):
        return None, False
    path = root / MANIFEST
    require(path.is_file() and not path.is_symlink(), 'nonempty unknown output directory: refusing overwrite')
    record = json.loads(path.read_text())
    require(record['format'] == FORMAT and record['schema'] in (1, 2) and record['root'] == str(root), 'unknown output ownership manifest')
    require(record['files'] == inventory(root), 'published files changed/unknown extra files: refusing overwrite')
    require(set(record['targets']) == set(TARGETS), 'incomplete target manifest')
    require(record['provenance']['signer'] == current_sources(project, flags)['signer'] and
            record['provenance']['source_archive_sha256'] == current_sources(project, flags)['source_archive_sha256'], 'published source provenance')
    require(record['signature']['verified'] and record['signature']['fingerprint'] == record['provenance']['signer'], 'published signature provenance')
    definitions_current = record['schema'] == 2
    for target, (sdk, platform) in TARGETS.items():
        target_record = record['targets'][target]
        flags_valid(target_record)
        recorded_flags = record['provenance']['flags']
        require(target_record['configure'][:len(recorded_flags)] == recorded_flags, 'published configure/source flags mismatch')
        require(target_record['bridge_source_sha256'] == record['provenance']['files']['shared/src/nativeInterop/c/linka_audio_decoder.c'] and
                target_record['bridge_header_sha256'] == record['provenance']['files']['shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h'], 'published bridge/source fingerprint mismatch')
        if not current_definition((root / target / 'linka_audio_decoder.def').read_text(), root / target):
            definitions_current = False
        if record['schema'] == 2:
            require(current_definition((root / target / 'linka_audio_decoder.def').read_text(), root / target), 'schema2 must use dynamic runtime')
            require(target_record['bridge_inputs_sha256'] == record['provenance']['files'], 'bridge input correspondence')
            verify_runtime(root / target, sdk, env)
        require(digest(root / target / 'include/linka_audio_decoder.h') == record['provenance']['files']['shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h'], 'published header/source mismatch')
        libraries = list((root / target / 'lib').glob('*.a'))
        require({p.name for p in libraries} == {'liblinka_audio_decoder.a','libavformat.a','libavcodec.a','libswresample.a','libavutil.a'}, 'published library set')
        for lib in libraries:
            require(digest(lib) == target_record['libraries'][lib.name]['sha256'], 'published archive correspondence mismatch')
            native_metadata(lib, platform, env)
    chain = toolchain(env)
    return record, definitions_current and record['provenance'] == current_sources(project, flags) and record['toolchain'] == chain


def publish(task, root, flags, prior):
    # All existing bytes were verified before any replacement; these are generated outputs only.
    _, already = inspect(root, task.project, flags, task.env)
    if already:
        task.result['gradle_outputs'] = dict(root=str(root), manifest=str(root / MANIFEST), status='VERIFIED_UNCHANGED')
        return prior
    root.mkdir(parents=True, exist_ok=True)
    staging = root / f'.publish-{uuid.uuid4()}'
    staging.mkdir()
    record = dict(format=FORMAT, schema=2, root=str(root), provenance=current_sources(task.project, flags),
                  toolchain=task.result['toolchain'], targets={}, artifact_run=str(task.root),
                  source_downloads=task.result['downloads'], signature=task.result['signature'])
    try:
        for target, (sdk, _) in TARGETS.items():
            source_record = task.result['development_targets'][sdk]
            prefix = Path(source_record['prefix'])
            destination = staging / target
            shutil.copytree(prefix / 'include', destination / 'include')
            shutil.copytree(prefix / 'lib', destination / 'lib')
            archive = next(Path(x['path']) for x in task.result['downloads'] if x['url'].endswith('/ffmpeg-8.1.2.tar.xz'))
            require(digest(archive) == record['provenance']['source_archive_sha256'], 'runtime source provenance')
            verify_runtime(prefix, sdk, task.env)
            shutil.copytree(prefix / RUNTIME, destination / RUNTIME)
            (destination / 'linka_audio_decoder.def').write_text(definition(root / target))
            build = prefix.parent
            record['targets'][target] = dict(source_record, config_text=(build / 'config.h').read_text(),
                components_text=(build / 'config_components.h').read_text(), make_config_text=(build / 'ffbuild/config.mak').read_text())
            flags_valid(record['targets'][target])
        # Caller serializes the one prepare task. Per-file replace + manifest-last is fail-closed
        # on interruption: a later producer refuses a mixed tree instead of guessing/rebuilding it.
        for path in sorted(staging.rglob('*')):
            if not path.is_file(): continue
            destination = root / path.relative_to(staging)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                relative = str(destination.relative_to(root))
                require(prior and prior['files'].get(relative) == digest(destination), 'refusing unknown output overwrite')
            os.replace(path, destination)
        shutil.rmtree(staging)  # Only the staging directory minted above.
        record['files'] = inventory(root)
        temporary = root / f'.manifest-{uuid.uuid4()}'
        temporary.write_text(json.dumps(record, indent=2) + '\n')
        os.replace(temporary, root / MANIFEST)
    except Exception:
        # Preserve outputs/evidence for explicit recovery, no broad cleanup/retry.
        raise
    _, valid = inspect(root, task.project, flags, task.env)
    require(valid, 'published manifest verification failed')
    task.result['gradle_outputs'] = dict(root=str(root), manifest=str(root / MANIFEST), status='VERIFIED')
    task.save()
    return record
