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

    return config;
  });
