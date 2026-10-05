// Runs `flutter test --coverage`, then enforces a minimum line-coverage
// threshold against the resulting coverage/lcov.info. Flutter has no
// built-in threshold enforcement; the maintained third-party options found
// (see docs/sessions/2026-08-19-s1-07-coverage-gates.md) either require a
// different coverage-collection pipeline or solve a different problem
// (PR-diff coverage), so this is a small standalone parser instead.
//
// Usage (run from mobile/, via the fvm-pinned SDK so the toolchain pin in
// S1-01 is respected):
//   fvm dart run tool/check_coverage.dart

import 'dart:io';

const double _threshold = 80;

/// The running `dart` executable lives under the Flutter SDK at
/// `<sdk>/bin/cache/dart-sdk/bin/dart`, so `flutter[.bat]` is not a direct
/// sibling. Walk up looking for `<ancestor>/bin/flutter[.bat]` instead of
/// hardcoding that depth, so this keeps working if the layout ever shifts.
String _findFlutterExecutable() {
  final flutterName = Platform.isWindows ? 'flutter.bat' : 'flutter';
  var dir = File(Platform.resolvedExecutable).parent;
  for (var i = 0; i < 6; i++) {
    final candidate = File('${dir.path}/bin/$flutterName');
    if (candidate.existsSync()) {
      return candidate.path;
    }
    final parent = dir.parent;
    if (parent.path == dir.path) break;
    dir = parent;
  }
  stderr.writeln(
    'Could not locate $flutterName near the running Dart SDK; '
    'falling back to "$flutterName" on PATH.',
  );
  return flutterName;
}

Future<void> main() async {
  final flutterExecutable = _findFlutterExecutable();

  stdout.writeln('Running "$flutterExecutable test --coverage"...');
  final testResult = await Process.run(
    flutterExecutable,
    ['test', '--coverage'],
    runInShell: true,
  );
  stdout.write(testResult.stdout);
  stderr.write(testResult.stderr);
  if (testResult.exitCode != 0) {
    stderr.writeln(
      'flutter test failed (exit ${testResult.exitCode}); coverage not checked.',
    );
    exit(testResult.exitCode);
  }

  final lcovFile = File('coverage/lcov.info');
  if (!lcovFile.existsSync()) {
    stderr.writeln(
      'coverage/lcov.info not found after flutter test --coverage.',
    );
    exit(1);
  }

  var linesFound = 0;
  var linesHit = 0;
  var countCurrentFile = true;
  for (final line in lcovFile.readAsLinesSync()) {
    if (line.startsWith('SF:')) {
      // Generated files (drift's *.g.dart) are never hand-edited (CLAUDE.md hard rule) and
      // carry thousands of lines of boilerplate (Row/Companion classes, table metadata) that
      // this project's own code never directly exercises line-by-line — counting them here
      // would make the gate measure drift's generator, not this project's code. Matches
      // analysis_options.yaml's own "**/*.g.dart" analyzer exclusion.
      countCurrentFile = !line.endsWith('.g.dart');
    } else if (line.startsWith('LF:') && countCurrentFile) {
      linesFound += int.parse(line.substring(3));
    } else if (line.startsWith('LH:') && countCurrentFile) {
      linesHit += int.parse(line.substring(3));
    }
  }

  if (linesFound == 0) {
    stderr.writeln('coverage/lcov.info has no LF records; nothing measured.');
    exit(1);
  }

  final percent = linesHit / linesFound * 100;
  stdout.writeln(
    'Line coverage: ${percent.toStringAsFixed(2)}% '
    '($linesHit/$linesFound lines), threshold ${_threshold.toStringAsFixed(0)}%',
  );

  if (percent < _threshold) {
    stderr.writeln(
      'FAILED: coverage ${percent.toStringAsFixed(2)}% is below the '
      '${_threshold.toStringAsFixed(0)}% threshold.',
    );
    exit(1);
  }

  stdout.writeln(
    'PASSED: coverage meets the ${_threshold.toStringAsFixed(0)}% threshold.',
  );
}
