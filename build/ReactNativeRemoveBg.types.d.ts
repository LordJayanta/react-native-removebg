/**
 * Which segmentation model to use.
 *
 * - `auto` – default. Picks the best engine available on the device and
 *   transparently falls back to the next best one if a model is missing.
 * - `subject` – general foreground/background separation. On Android this model
 *   is downloaded on demand by Google Play services (~5 MB, then cached and
 *   available offline). On iOS this is part of the OS, no download needed.
 * - `selfie` – people-only segmentation. Bundled in the app, works fully
 *   offline, no Play services required.
 * - `person` – iOS only, alias for the person-segmentation fallback used on
 *   iOS versions below 17.
 */
export type RemoveBgEngine = 'auto' | 'subject' | 'selfie' | 'person';
export type RemoveBgOptions = {
    /**
     * Segmentation engine to use. Defaults to `auto`.
     */
    engine?: RemoveBgEngine;
    /**
     * Crop the output to the bounding box of the detected foreground.
     * Defaults to `false` (output keeps the input dimensions).
     */
    crop?: boolean;
    /**
     * Return a grayscale matte instead of a colour image with transparency.
     * Defaults to `false`.
     */
    maskOnly?: boolean;
    /**
     * Foreground confidence (0–1) required to keep a pixel fully opaque.
     * Defaults to `0.5`.
     */
    threshold?: number;
    /**
     * Width of the soft transition band around the mask edge (0–1), used to
     * avoid jagged cutouts. Defaults to `0.08`.
     */
    feather?: number;
    /**
     * Longest edge, in pixels, of the image handed to the model. The source image
     * is downscaled (preserving aspect ratio) when it exceeds this value, which
     * also caps memory usage. Defaults to `4096`; pass `0` to disable.
     */
    maxSize?: number;
};
export type RemoveBgResult = {
    /** `file://` URI of the written PNG, inside the app cache directory. */
    uri: string;
    /** Width of the produced image in pixels. */
    width: number;
    /** Height of the produced image in pixels. */
    height: number;
    /** The engine that actually ran, after fallback resolution. */
    engine: string;
    /** Whether the result was cropped to the foreground bounding box. */
    cropped: boolean;
    /** Wall-clock time spent inside the native module, in milliseconds. */
    durationMs: number;
};
export type RemoveBgCapabilities = {
    /** `ios` or `android`. */
    platform: string;
    /** Operating system version string. */
    osVersion: string;
    /** Every engine that can run on this device, most capable first. */
    engines: RemoveBgEngine[];
    /** Engine used when `engine` is `auto`. */
    defaultEngine: RemoveBgEngine;
    /**
     * `true` when the default engine needs a one-time model download before the
     * first successful call (Android `subject` engine only).
     */
    requiresModelDownload: boolean;
    /**
     * Android only. `true` when Google Play services is installed, i.e. the
     * `subject` model is downloadable and its result can be trusted.
     */
    playServicesAvailable?: boolean;
};
//# sourceMappingURL=ReactNativeRemoveBg.types.d.ts.map