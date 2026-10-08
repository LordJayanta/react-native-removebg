# react-native-removebg

On-device background removal for iOS and Android, built as an Expo module.
No uploads, no API keys, no server — segmentation runs entirely on the phone.

> **Status:** Android is verified end to end (built from a git install into a
> fresh Expo app). iOS is implemented but **not yet compiled** — see
> [Platform support](#platform-support).

## Install

Install straight from git:

```sh
npx expo install react-native-removebg@git+https://github.com/LordJayanta/react-native-removebg.git
```

Pin a tag or commit for reproducible builds:

```sh
# a tag
npx expo install react-native-removebg@git+https://github.com/LordJayanta/react-native-removebg.git#v0.1.0

# an exact commit
npx expo install react-native-removebg@git+https://github.com/LordJayanta/react-native-removebg.git#abc1234
```

Or add it to `package.json` by hand:

```json
{
  "dependencies": {
    "react-native-removebg": "git+https://github.com/LordJayanta/react-native-removebg.git"
  }
}
```

Then install:

```sh
npx expo install
```

And add the bundled config plugin to your app config:

```json
{
  "expo": {
    "plugins": ["react-native-removebg"]
  }
}
```

The plugin is what keeps debug builds installable alongside `expo-dev-client`
(see [Android](#android)). There is nothing else to configure.

### Requirements

| | |
| --- | --- |
| iOS | 16.0+ (16.4 recommended) |
| Android | API 24+ |
| Expo | SDK 57 / React Native 0.86 |

The package ships native code, so it needs a **development build**. It will not
load in Expo Go.

```sh
npx expo prebuild --clean
npx expo run:ios       # or: npx expo run:android
```

For cloud builds:

```sh
npx eas-cli build --profile development
```

### Package managers

| Manager | Status | Notes |
| --- | --- | --- |
| **bun** | Verified | Works — this package runs **no install scripts**. |
| **npm** | Verified | Same: no lifecycle scripts, so nothing to trust or allow. |
| **pnpm** | Verified | Same. Nothing to add to `allowBuilds` in `pnpm-workspace.yaml`. Peer deps are all `"*"`, so it binds to your app's `expo`/`react`/`react-native`. |
| **yarn** | Verified | Classic (`node-modules` linker) behaves the same. |

**Node** itself is just the runtime — resolution worked under
`node -e "require.resolve('react-native-removebg')"` in both installs tested.

The package intentionally declares **no `prepare`/`postinstall` script**. `build/`
is committed, so `main: build/index.js` resolves straight from the git checkout.
That is what keeps installs working on pnpm (which hard-errors on unapproved
git-dep build scripts), bun, and Yarn PnP — and it avoids making every consumer
download this package's devDependencies just to run `tsc`.

If you hit `Cannot find module .../build/index.js` after installing, you are on a
commit older than `a04b85b` — reinstall to get the committed `build/`.

### If you use yarn

Yarn Berry's default **PnP mode stores no `node_modules` directories**, and Expo
autolinking plus Gradle and CocoaPods all expect real `node_modules`. Use Yarn
Classic, or set this in `.yarnrc.yml`:

```yaml
nodeLinker: node-modules
```

## Platform support

### Android

**Verified.** Built end to end from a git install: `:app:assembleDebug`
succeeds, and all module classes appear in the APK's `classes8.dex`. Autolinking
resolves `expo.modules.removebg.ReactNativeRemoveBgModule`, and the ML Kit
`subject_segment` auto-download entry lands in the merged `AndroidManifest.xml`.

**Works with `expo-dev-client`.** `expo-dev-launcher` asks Play services to prefetch
a different ML Kit model in debug builds, and that collides with this module's
manifest entry. The bundled config plugin settles it — add
`"react-native-removebg"` to `expo.plugins` and `npx expo run:android` works. Without
the plugin, see [ML Kit manifest conflict](#ml-kit-manifest-conflict-expo-dev-client).

### iOS

Supported, but treat it as **unproven** until you build it on a Mac.

Implemented against Vision:

- iOS 17+ → `VNGenerateForegroundInstanceMaskRequest` (any subject, in the OS)
- iOS 16 and older → `VNGeneratePersonSegmentationRequest` (people only)
- Minimum deployment target 16.0

**Verified:** autolinking resolves the `ReactNativeRemoveBg` pod from the
installed package, and `expo-module.config.json` sets `apple.podspecPath`
explicitly (autolinking's directory scan skips symlinked podspecs, so this is
deliberate).

**Not verified:** no macOS or Xcode was available while writing this, so
`pod install` has never run and the Swift has never been compiled. Expect to fix
compile errors on the first attempt — the Kotlin side went through several rounds
of this, because compiling against the real ML Kit artifacts caught API mistakes
the documentation did not.

If iOS fails to build, the likeliest spot is the `Mask` type's `CVPixelBuffer`
handling in `ios/RemoveBgCore.swift`. That is where the code reasons about pixel
formats and row order (a bitmap context is bottom-up while Vision masks are
top-down, so sampling flips `v`) rather than checking them against a compiler.
Please open an issue with the error if it happens.

## Quick start

```tsx
import { useState } from 'react';
import { Button, Image, View } from 'react-native';
import { removeBackground } from 'react-native-removebg';

export default function Screen() {
  const [uri, setUri] = useState<string | null>(null);

  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
      {uri && <Image source={{ uri }} style={{ width: 240, height: 240 }} />}
      <Button
        title="Remove background"
        onPress={async () => {
          const result = await removeBackground('file:///path/to/photo.jpg');
          setUri(result.uri);
        }}
      />
    </View>
  );
}
```

## API

### `removeBackground(uri, options?): Promise<RemoveBgResult>`

Runs segmentation and writes a transparent PNG into the app cache directory.

`uri` is a `file://` URI or a plain path to an image.

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `engine` | `'auto' \| 'subject' \| 'selfie' \| 'person'` | `'auto'` | Which model to use. See [Engines](#engines). |
| `crop` | `boolean` | `false` | Trim the output to the foreground bounding box. |
| `maskOnly` | `boolean` | `false` | Return an opaque grayscale matte instead of the photo. |
| `threshold` | `number` | `0.5` | Foreground confidence (0–1) needed to stay fully opaque. |
| `feather` | `number` | `0.08` | Width of the soft edge band (0–0.5) to avoid jagged cutouts. |
| `maxSize` | `number` | `4096` | Longest edge, in pixels, fed to the model. `0` disables the cap. |

Resolves to:

```ts
{
  uri: string;        // file:// URI of the written PNG
  width: number;      // output width in pixels
  height: number;     // output height in pixels
  engine: string;     // engine that actually ran, after fallback
  cropped: boolean;   // whether the result was cropped
  durationMs: number; // time spent in native code
}
```

### `getCapabilities(): Promise<RemoveBgCapabilities>`

Reports what the device can run. Useful for warning users about the Android
model download.

```ts
const caps = await getCapabilities();
// {
//   platform: 'android',
//   osVersion: '15',
//   engines: ['subject', 'selfie'],
//   defaultEngine: 'auto',
//   requiresModelDownload: true,
//   playServicesAvailable: true,
// }
```

### `prepare(engine?): Promise<{ engine: string }>`

Loads the model ahead of time so the first `removeBackground` call is fast.
Safe to call repeatedly; a failure here is not fatal.

```ts
useEffect(() => {
  prepare().catch(() => {});
}, []);
```

## Engines

The platforms offer genuinely different options, and the differences matter if
you need offline behaviour or non-human subjects.

| Platform | Engine | Segments | Offline |
| --- | --- | --- | --- |
| iOS 17+ | Vision `VNGenerateForegroundInstanceMaskRequest` | anything | yes, built into the OS |
| iOS 16 and older | Vision `VNGeneratePersonSegmentationRequest` | people | yes, built into the OS |
| Android | ML Kit `play-services-mlkit-subject-segmentation` | anything | after a one-time ~5 MB download |
| Android | ML Kit `segmentation-selfie` | people | yes, bundled in the APK |

### The Android caveat

**Google does not publish a bundled general-subject model for Android.** Only
`play-services-mlkit-subject-segmentation` handles arbitrary subjects, and it
fetches its model through Google Play services.

So `auto` tries `subject` first and silently falls back to the bundled `selfie`
model, which always works. Practical consequences:

- Without Play services (or before the model has downloaded), `auto` succeeds but
  only segments people. Products, pets and objects will not cut out.
- `getCapabilities().requiresModelDownload` is `true` on Android — surface it if
  a slow first cutout would confuse users.
- The module ships a `DEPENDENCIES` manifest entry asking Play to prefetch the
  model, but that only happens automatically for builds installed from the Play
  Store. Development and sideloaded builds fetch on first use.

If you need a strictly offline APK with no Play services dependency, pass
`engine: 'selfie'` explicitly and commit to people-only segmentation. Or use iOS,
where the general-subject model ships with the OS.

## Notes and limits

- Output goes to the **cache directory**, which iOS and Android may reclaim. Copy
  the file somewhere durable if you need to keep it.
- Inference runs on a background queue, and calls are serialised per module to
  keep peak memory predictable. A 12 MP photo needs roughly 150 MB while
  processing; lower `maxSize` if that is too much for your target devices.
- The mask is produced at model resolution (256×256) and sampled bilinearly, so
  very soft edges like hair can look slightly smooth. Raise `feather` to soften.
- ML Kit wants inputs of at least 256×256 for good results, and blurry input
  degrades quality.

## Troubleshooting

**`NativeModule: null` or the module is missing at runtime.** You are probably
in Expo Go. Build a development binary with `npx expo run:ios|android`.

**The iOS pod is not installed.** Autolinking skips a podspec that is a symlink.
The module sets `apple.podspecPath` in `expo-module.config.json` to avoid this,
but if you vendor the sources yourself, re-run `npx pod-install`.

**Cutout looks empty or finds no subject.** On Android this usually means the
Play services model has not downloaded and the people-only fallback was used on
a non-human subject. Check `getCapabilities().playServicesAvailable`.

**Android build fails with a duplicate native module.** Another copy of the
module is installed. Remove the local `modules/react-native-removebg` folder if
you also added the package from git.

**Expo builds or autolinking cannot find the module.** Check for an overly broad
ignore rule. `expo-doctor` flags this: if `modules/**/android/build.gradle` or
`modules/**/ios/*.podspec` matches inside a nested `node_modules`, it will
misreport that a local module's native sources are gitignored.

### ML Kit manifest conflict (`expo-dev-client`)

**You should not hit this** — the bundled config plugin fixes it. If your app config
does not list `"react-native-removebg"` under `expo.plugins`, every **debug** build
fails at `:app:processDebugMainManifest`:

```
Manifest merger failed : Attribute meta-data#com.google.mlkit.vision.DEPENDENCIES@value
value=(barcode_ui) from [:expo-dev-launcher] AndroidManifest.xml:16:13-39
    is also present at [:react-native-removebg] AndroidManifest.xml:18:13-44 value=(subject_segment).
```

Why: `com.google.mlkit.vision.DEPENDENCIES` is one app-wide slot. This module
declares `subject_segment` in it; `expo-dev-launcher` declares `barcode_ui` from its
`debug` source set. Two different values for the same `android:name` is an
unresolvable manifest-merger conflict, and the value is *not* a list the merger will
join — verified against AGP 9 / Gradle 9.3, both of these fail with the identical
error, in a library manifest *and* in the app manifest:

- `tools:node="merge"`
- `tools:replace="android:value"`

So `app.plugin.js` adds the entry to **your app's** manifest instead, where
`tools:replace` is honoured, because the app outranks every library. Release builds
were never affected: `expo-dev-launcher` declares `barcode_ui` in `debug` only.

Manual equivalent, if you cannot use a config plugin (bare RN CLI, or you prefer to
patch the manifest yourself). Remember to keep it across `expo prebuild`, which
regenerates `android/`:

```xml
<!-- android/app/src/main/AndroidManifest.xml -->
<meta-data
  android:name="com.google.mlkit.vision.DEPENDENCIES"
  android:value="subject_segment"
  tools:replace="android:value"/>
```

If another dependency prefetches a model of its own, list it in `android:value`
alongside `subject_segment` — the app's value replaces every library's, so anything
missing from it is dropped from the merged manifest without any build error.

## Contributing

```sh
bun install
bun run build      # tsc -> build/
bun run typecheck
bun run lint
```

Native code changes require a rebuild of the app, not just a Metro reload.

### Releasing a version

`build/` is committed on purpose (see the note in `.gitignore`), so source and
build output always ship together:

```sh
# 1. edit src/
# 2. regenerate
bun run build
# 3. bump version in package.json
# 4. commit src/, build/, and package.json
git commit -am "feat: ..."
# 5. tag — consumers can now pin with #v0.2.0
git tag -a v0.2.0 -m "v0.2.0" && git push --tags
```

Push `build/` even when only docs changed? It does not hurt, but keeping it in
sync is only strictly necessary when `src/` changes.

## License

MIT