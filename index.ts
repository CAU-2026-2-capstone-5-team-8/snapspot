import { registerRootComponent } from 'expo';

// Background tasks must be defined at the top level, before the app component registers.
import './src/background';

import App from './App';

registerRootComponent(App);
