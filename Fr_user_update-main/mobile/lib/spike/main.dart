// S1-02 Uqudo device spike -- a SEPARATE entrypoint, never the real app.
//
//   fvm flutter build apk --debug -t lib/spike/main.dart \
//     --dart-define=SPIKE_API_BASE_URL=http://<laptop-lan-ip>:8080 \
//     --dart-define=SPIKE_KEY=<UQUDO_SPIKE_KEY from the repo-root .env>
//
// Deliberately no ProviderScope, no router, no drift, no secure storage: none of the real app's
// providers are reachable from here, so the spike exercises the SDK and nothing else. Throwaway
// scaffolding -- the real stage 7-12 screens are S5-07/S5-08, built to the journey spec.
import 'package:flutter/material.dart';

import 'uqudo_spike_screen.dart';

void main() {
  runApp(
    const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: UqudoSpikeScreen(),
    ),
  );
}
