import type { RemoveBgCapabilities, RemoveBgEngine, RemoveBgOptions, RemoveBgResult } from './ReactNativeRemoveBg.types';
export { default } from './ReactNativeRemoveBgModule';
export * from './ReactNativeRemoveBg.types';
/**
 * Removes the background from a local image, entirely on device.
 *
 * @param uri `file://` URI (or a plain path) of the source image.
 * @param options Engine selection and output tuning. See {@link RemoveBgOptions}.
 * @returns The URI and dimensions of the written transparent PNG.
 */
export declare function removeBackground(uri: string, options?: RemoveBgOptions): Promise<RemoveBgResult>;
/**
 * Reports which segmentation engines this device can run.
 *
 * Useful to warn users that the first Android call may need a model download.
 */
export declare function getCapabilities(): Promise<RemoveBgCapabilities>;
/**
 * Loads the model ahead of time so the first `removeBackground` call is fast.
 *
 * Safe to call repeatedly; a failure here is not fatal because
 * `removeBackground` still resolves the engine at call time.
 */
export declare function prepare(engine?: RemoveBgEngine): Promise<{
    engine: string;
}>;
//# sourceMappingURL=index.d.ts.map