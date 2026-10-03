import NativeModule from './ReactNativeRemoveBgModule';
// Reexport the native module. On web, it will be resolved to ReactNativeRemoveBgModule.web.ts
// and on native platforms to ReactNativeRemoveBgModule.ts
export { default } from './ReactNativeRemoveBgModule';
export * from './ReactNativeRemoveBg.types';
const ENGINES = ['auto', 'subject', 'selfie', 'person'];
const DEFAULTS = {
    engine: 'auto',
    crop: false,
    maskOnly: false,
    threshold: 0.5,
    feather: 0.08,
    maxSize: 4096,
};
function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}
function normalizeOptions(options) {
    const engine = options?.engine ?? DEFAULTS.engine;
    if (!ENGINES.includes(engine)) {
        throw new Error(`react-native-removebg: unknown engine "${engine}". Expected one of: ${ENGINES.join(', ')}.`);
    }
    const maxSize = options?.maxSize ?? DEFAULTS.maxSize;
    if (!Number.isFinite(maxSize) || maxSize < 0) {
        throw new Error('react-native-removebg: `maxSize` must be a non-negative number.');
    }
    return {
        engine,
        crop: options?.crop ?? DEFAULTS.crop,
        maskOnly: options?.maskOnly ?? DEFAULTS.maskOnly,
        threshold: clamp(options?.threshold ?? DEFAULTS.threshold, 0, 1),
        feather: clamp(options?.feather ?? DEFAULTS.feather, 0, 0.5),
        maxSize: Math.floor(maxSize),
    };
}
/**
 * Removes the background from a local image, entirely on device.
 *
 * @param uri `file://` URI (or a plain path) of the source image.
 * @param options Engine selection and output tuning. See {@link RemoveBgOptions}.
 * @returns The URI and dimensions of the written transparent PNG.
 */
export async function removeBackground(uri, options) {
    if (!uri) {
        throw new Error('react-native-removebg: `uri` is required.');
    }
    return NativeModule.removeBackgroundAsync(uri, normalizeOptions(options));
}
/**
 * Reports which segmentation engines this device can run.
 *
 * Useful to warn users that the first Android call may need a model download.
 */
export function getCapabilities() {
    return NativeModule.getCapabilities();
}
/**
 * Loads the model ahead of time so the first `removeBackground` call is fast.
 *
 * Safe to call repeatedly; a failure here is not fatal because
 * `removeBackground` still resolves the engine at call time.
 */
export function prepare(engine) {
    return NativeModule.prepare(engine);
}
//# sourceMappingURL=index.js.map