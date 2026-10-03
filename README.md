# react-native-removebg

On-device background removal for iOS and Android, built as an Expo module.
No uploads, no API keys, no server — segmentation runs entirely on the phone.

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

## Contributing

```sh
bun install
bun run build      # tsc -> build/
bun run typecheck
bun run lint
```

Native code changes require a rebuild of the app, not just a Metro reload.

## License

MIT