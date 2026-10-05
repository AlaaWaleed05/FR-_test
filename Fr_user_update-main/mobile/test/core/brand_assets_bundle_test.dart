import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// **Every asset a widget draws must also be declared in `pubspec.yaml`.**
///
/// This exists because of a defect S8-16 shipped past its whole gate suite: `LaunchScreen` drew
/// `assets/brand/sfb-wordmark-navy.png`, the `assets:` list did not include it, and the splash
/// threw "Unable to load asset" on a real device while `fvm flutter analyze` reported no issues
/// and 604 tests passed. Found by `@agent-reviewer`, who checked the built bundle rather than
/// the widget tree.
///
/// **A widget test cannot catch this and never will.** `find.byType(Image)` inspects the WIDGET
/// TREE — the `Image` widget is constructed correctly whether or not its bytes exist — and the
/// test harness resolves assets through a stub that answers for anything. The declaration and
/// the reference are two separate facts, and only comparing them directly relates the two.
///
/// So this reads the source of both: it greps `lib/` for `assets/...` string literals and checks
/// each against `pubspec.yaml`'s `assets:` block. It deliberately does NOT go the other way —
/// an asset declared but unused is waste, not breakage, and the pearl/wordmark generator emits
/// one such file on purpose (see `generate_design3_assets.py`).
void main() {
  test('every asset referenced in lib/ is declared in pubspec.yaml', () {
    final declared = _declaredAssets();
    expect(
      declared,
      isNotEmpty,
      reason: 'the pubspec parse found nothing — this test would then pass vacuously',
    );

    final missing = <String, String>{};
    final pattern = RegExp(r"'(assets/[A-Za-z0-9_\-./]+\.[A-Za-z0-9]+)'");

    for (final entity in Directory('lib').listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart')) continue;
      for (final match in pattern.allMatches(entity.readAsStringSync())) {
        final asset = match.group(1)!;
        // A directory declaration (`assets/brand/`) covers every file directly inside it, which
        // is how Flutter itself resolves them.
        final covered = declared.contains(asset) ||
            declared.any((d) => d.endsWith('/') && asset.startsWith(d));
        if (!covered) missing[asset] = entity.path;
      }
    }

    expect(
      missing,
      isEmpty,
      reason:
          'These assets are drawn by a widget but are not in pubspec.yaml\'s `assets:` block, so '
          'they are NOT in the built bundle and will throw "Unable to load asset" at runtime — '
          'while analyze and every widget test still pass. '
          '${missing.entries.map((e) => '${e.key} (${e.value})').join('; ')}',
    );
  });
}

/// The `assets:` entries from `pubspec.yaml`.
///
/// Hand-parsed rather than pulled through a YAML package: this file is the only consumer, the
/// block is a flat list of `    - path` lines, and adding a dev dependency to read six lines
/// would be the more expensive answer.
Set<String> _declaredAssets() {
  final lines = File('pubspec.yaml').readAsLinesSync();
  final assets = <String>{};
  var inBlock = false;

  for (final line in lines) {
    final trimmed = line.trim();
    if (trimmed.startsWith('#') || trimmed.isEmpty) continue;

    if (trimmed == 'assets:') {
      inBlock = true;
      continue;
    }
    if (!inBlock) continue;

    if (trimmed.startsWith('- ')) {
      assets.add(trimmed.substring(2).trim());
      continue;
    }
    // Any other key at the same or lower indentation ends the block.
    inBlock = false;
  }
  return assets;
}
