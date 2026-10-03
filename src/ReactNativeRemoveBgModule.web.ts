import { registerWebModule, NativeModule } from 'expo';

import type { ReactNativeRemoveBgNativeModule } from './ReactNativeRemoveBgModule';

// react-native-removebg has no web implementation: segmentation runs in native
// code only. The stub exists so web bundles resolve this import instead of
// crashing on a missing native module.
class ReactNativeRemoveBgModule extends NativeModule {}

registerWebModule(ReactNativeRemoveBgModule, 'ReactNativeRemoveBg');

export default new ReactNativeRemoveBgModule() as unknown as ReactNativeRemoveBgNativeModule;
