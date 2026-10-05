import 'dart:typed_data';

import 'manifest_dto.dart';

/// Transport boundary for AD-002f's two reference-data endpoints
/// (docs/components/reference-data.md). Kept as a thin interface, separate from
/// `ReferenceRepository`'s verify/cache logic, so that logic is unit-testable with a hand-written
/// fake instead of a real network or a mocking framework.
abstract class ReferenceApi {
  /// `GET /api/v1/reference/manifest`. [ifNoneMatch], when supplied, is sent as the
  /// `If-None-Match` request header; a 304 response is surfaced as a `null` return so the caller
  /// can keep using its cached manifest without treating "nothing changed" as an error.
  Future<ManifestDto?> fetchManifest({String? ifNoneMatch});

  /// `GET /api/v1/reference/lists/{listCode}/{version}`. Returns the RAW response bytes,
  /// untouched — the caller must hash these exact bytes and compare against the manifest's
  /// `contentHash` BEFORE parsing them as JSON (docs/components/reference-data.md's one
  /// integrity primitive: "the client hashes what it received").
  Future<Uint8List> fetchListBytes(String listCode, int version);
}
