const { withAndroidManifest } = require('expo/config-plugins');

// Play services prefetches ML Kit models listed in this meta-data after a Play
// Store install. It is one app-wide slot, so any two libraries that declare it
// break the Android manifest merger -- this module asks for `subject_segment`,
// expo-dev-launcher asks for `barcode_ui` in its debug source set. Neither
// `tools:node="merge"` nor `tools:replace` works from inside a library manifest
// (verified against AGP 9 / Gradle 9.3); only the app manifest can settle it,
// because the app outranks every library in the merger.
const NAME = 'com.google.mlkit.vision.DEPENDENCIES';

module.exports = (config) =>
  withAndroidManifest(config, (config) => {
    const application = config.modResults.manifest.application[0];
    const metaData = (application['meta-data'] ??= []);
    const existing = metaData.find((tag) => tag.$['android:name'] === NAME);

    // Idempotent, and leaves a value an earlier plugin already claimed alone.
    if (existing) {
      existing.$['tools:replace'] = 'android:value';
      return config;
    }

    metaData.push({
      $: {
        'android:name': NAME,
        'android:value': 'subject_segment',
        'tools:replace': 'android:value',
      },
    });

    // Another library's declaration (expo-dev-launcher asks for `barcode_ui` from its
    // debug source set) is invisible at config time, and this entry outranks every
    // library, so theirs would be dropped from the merged manifest without any build
    // error. Say so rather than let the prefetch disappear quietly.
    console.warn(
      `[react-native-removebg] added ${NAME}=subject_segment to the app manifest. ` +
        'If another dependency asks Play services for an ML Kit model, list it in that ' +
        'value too (comma separated) -- this plugin cannot see models declared by other ' +
        'libraries.'
    );

    return config;
  });
