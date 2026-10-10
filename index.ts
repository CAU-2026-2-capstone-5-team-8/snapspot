// Background tasks must be defined at the top level, before the router loads any screen,
// so they exist when Android/iOS wakes the app up for a geofence event.
import './src/background';

import 'expo-router/entry';
