/// Backend base URL — the tenant/endpoint-configuration discipline CLAUDE.md requires for
/// Uqudo applies equally here: never hardcode a deployment target into a build. Overridden via
/// `--dart-define=REFERENCE_API_BASE_URL=...` (used identically by the app and by
/// `bin/live_reference_fetch_proof.dart`, so both share one override mechanism).
abstract final class AppConfig {
  static const String referenceApiBaseUrl = String.fromEnvironment(
    'REFERENCE_API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );
}
