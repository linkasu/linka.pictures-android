"""Verified generated-prefix publication. Never consumes/truncates an unknown directory."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import uuid

MANIFEST = 'linka-audio-decoder-manifest.json'
FORMAT = 'su.linka.development.ios-audio-decoder'
TARGETS = {'iosSimulatorArm64': ('iphonesimulator', '7'), 'iosArm64': ('iphoneos', '2')}


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


def toolchain(env):
    result = {'xcode': command(['xcodebuild', '-version'], env)}
    require(result['xcode'].startswith('Xcode 27.2\n'), 'Xcode27.2 required')
    for sdk, _ in TARGETS.values():
        result[sdk] = {key: command(['xcrun', '--sdk', sdk, option], env).strip()
                       for key, option in [('path', '--show-sdk-path'), ('version', '--show-sdk-version')]}
        result[sdk]['clang'] = command(['xcrun', '--sdk', sdk, '--find', 'clang'], env).strip()
        result[sdk]['clang_version'] = command([result[sdk]['clang'], '--version'], env)
    return result


def definition(prefix):
    return f'''headers = linka_audio_decoder.h
headerFilter = linka_audio_decoder.h
package = su.linka.pictures.platform.audio.decoder
compilerOpts = -I{prefix}/include
staticLibraries = liblinka_audio_decoder.a libavformat.a libavcodec.a libswresample.a libavutil.a
libraryPaths = {prefix}/lib
linkerOpts = -lm -framework CoreFoundation -framework CoreVideo -framework CoreMedia
'''


def current_definition(actual, prefix):
    expected = definition(prefix)
    # Known schema1 seed used clang's -pthread driver flag; K/N invokes ld directly.
    legacy = expected.replace('linkerOpts = -lm ', 'linkerOpts = -lm -pthread ')
    require(actual in (expected, legacy), 'published def path/package mismatch')
    return actual == expected


def inventory(root):
    files = {}
    for path in root.rglob('*'):
        require(not path.is_symlink(), 'generated outputs must be self-contained, not symlinks')
        if path == root / MANIFEST:
            continue
        files[str(path.relative_to(root))] = 'directory' if path.is_dir() else digest(path)
    return files


def native_metadata(path, platform, env):
    require(command(['xcrun', 'lipo', '-archs', path], env).strip() == 'arm64', 'published architecture')
    load = command(['xcrun', 'otool', '-l', path], env)
    builds = re.findall(r'cmd LC_BUILD_VERSION\s+cmdsize \d+\s+platform (\S+)\s+minos (\S+)', load)
    members = command(['xcrun', 'ar', '-t', path], env)
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
    names = ['shared/src/nativeInterop/c/linka_audio_decoder.c',
             'shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h']
    return {'files': {name: digest(project / name) for name in names},
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
    require(record['format'] == FORMAT and record['schema'] == 1 and record['root'] == str(root), 'unknown output ownership manifest')
    require(record['files'] == inventory(root), 'published files changed/unknown extra files: refusing overwrite')
    require(set(record['targets']) == set(TARGETS), 'incomplete target manifest')
    require(record['provenance']['signer'] == current_sources(project, flags)['signer'] and
            record['provenance']['source_archive_sha256'] == current_sources(project, flags)['source_archive_sha256'], 'published source provenance')
    require(record['signature']['verified'] and record['signature']['fingerprint'] == record['provenance']['signer'], 'published signature provenance')
    definitions_current = True
    for target, (_, platform) in TARGETS.items():
        target_record = record['targets'][target]
        flags_valid(target_record)
        recorded_flags = record['provenance']['flags']
        require(target_record['configure'][:len(recorded_flags)] == recorded_flags, 'published configure/source flags mismatch')
        require(target_record['bridge_source_sha256'] == record['provenance']['files']['shared/src/nativeInterop/c/linka_audio_decoder.c'] and
                target_record['bridge_header_sha256'] == record['provenance']['files']['shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h'], 'published bridge/source fingerprint mismatch')
        if not current_definition((root / target / 'linka_audio_decoder.def').read_text(), root / target):
            definitions_current = False
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
    record = dict(format=FORMAT, schema=1, root=str(root), provenance=current_sources(task.project, flags),
                  toolchain=toolchain(task.env), targets={}, artifact_run=str(task.root),
                  source_downloads=task.result['downloads'], signature=task.result['signature'])
    try:
        for target, (sdk, _) in TARGETS.items():
            source_record = task.result['development_targets'][sdk]
            prefix = Path(source_record['prefix'])
            destination = staging / target
            shutil.copytree(prefix / 'include', destination / 'include')
            shutil.copytree(prefix / 'lib', destination / 'lib')
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
