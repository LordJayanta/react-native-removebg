import { requireNativeModule } from 'expo';

import type {
  RemoveBgCapabilities,
  RemoveBgEngine,
  RemoveBgOptions,
  RemoveBgResult,
} from './ReactNativeRemoveBg.types';

type NativeOptions = Omit<Required<RemoveBgOptions>, 'engine'> & { engine: RemoveBgEngine };

export type ReactNativeRemoveBgNativeModule = {
  removeBackgroundAsync(uri: string, options: NativeOptions): Promise<RemoveBgResult>;
  getCapabilities(): Promise<RemoveBgCapabilities>;
  /** Warms up the model so the first real call is faster. */
  prepare(engine?: RemoveBgEngine): Promise<{ engine: string }>;
};

export default requireNativeModule<ReactNativeRemoveBgNativeModule>('ReactNativeRemoveBg');
