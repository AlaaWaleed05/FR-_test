import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/journey/journey_error.dart';
import 'package:mobile/core/journey/journey_providers.dart';
import 'package:mobile/core/signature/signature_api.dart';
import 'package:mobile/core/signature/signature_models.dart';
import 'package:mobile/core/signature/signature_providers.dart';
import 'package:mobile/features/signature/stage11_screen.dart';
import 'package:signature/signature.dart' as sig;

import '../../core/liveness/fakes.dart';

class FakeSignatureApi implements SignatureApi {
  Object? errorToThrow;
  int callCount = 0;
  CapturedSignature? lastSignature;

  @override
  Future<void> submit({
    required String profileId,
    required CapturedSignature signature,
  }) async {
    callCount++;
    lastSignature = signature;
    final error = errorToThrow;
    if (error != null) throw error;
  }
}

/// Stage 11 — signature.
void main() {
  Future<({FakeSignatureApi api, String? Function() landedPath})> pumpStage11(
    WidgetTester tester,
  ) async {
    final db = SessionDatabase.forTesting();
    addTearDown(db.close);
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '16',
            verifiedAccountNumber: '0000000001',
            resumeStage: resumeStageBeyondStage9,
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );

    final api = FakeSignatureApi();
    String? landedPath;

    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landedPath = path;
        return Scaffold(body: Text('landed $path'));
      },
    );

    final router = GoRouter(
      initialLocation: '/stage-11',
      routes: [
        GoRoute(
          path: '/stage-11',
          builder: (context, state) => const Stage11Screen(),
        ),
        stub('/stage-10'),
        stub('/stage-12'),
        stub('/final-stages'),
        GoRoute(
          path: '/terminal',
          builder: (context, state) {
            landedPath = '/terminal';
            return Scaffold(body: Text(state.extra as String));
          },
        ),
        GoRoute(
          path: '/ended',
          builder: (context, state) {
            landedPath = '/ended';
            return const Scaffold(body: Text('ended'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(db),
          signatureApiProvider.overrideWithValue(api),
          journeyApiProvider.overrideWithValue(FakeJourneyApi()),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: api, landedPath: () => landedPath);
  }

  /// Draws a stroke on the pad, which is what makes the submit button live.
  Future<void> draw(WidgetTester tester) async {
    await tester.drag(find.byType(sig.Signature), const Offset(60, 30));
    await tester.pumpAndSettle();
  }

  /// Taps Next and lets the submission actually finish.
  ///
  /// Two things make this more than a `pumpAndSettle`. Submitting shows a
  /// `CircularProgressIndicator`, which animates indefinitely, so "settled" never arrives. And
  /// exporting the pad calls `toPngBytes`, which rasterises through the engine — real asynchronous
  /// work that fake-time pumping does not advance, so it needs `runAsync` to make progress at all.
  Future<void> submit(WidgetTester tester) async {
    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 200)),
    );
    await tester.pump();
    await tester.pump();
  }

  testWidgets('offers BOTH routes, neither presented as preferred', (
    tester,
  ) async {
    await pumpStage11(tester);

    // customer.md: "Draw on screen ... Upload an image — from the device's photo library or
    // camera ... Neither is preferred; the customer chooses."
    //
    // S8-05 (walk comment 12a) made the two routes an explicit choice instead of a drawing pad
    // with two buttons underneath it. The draw route IS preselected, deliberately: an empty
    // initial selection would honour "neither is preferred" most literally, but it would also
    // make drawing cost one tap where it previously cost none, on a mandated journey for a
    // mixed-literacy audience. So the tap balance is preserved exactly — pad immediate, upload
    // one tap, neither route made harder than before — and that balance is what is asserted
    // below, rather than merely the existence of two buttons.
    expect(find.widgetWithText(SegmentedButton<bool>, 'التوقيع على الشاشة'), findsOneWidget);
    expect(find.widgetWithText(SegmentedButton<bool>, 'رفع صورة'), findsOneWidget);
    // The pad is present from the first frame, exactly as before this change — drawing still
    // costs zero taps and uploading still costs one, so neither route was made harder.
    expect(find.byType(sig.Signature), findsOneWidget);

    // And the upload route is reachable in that one tap, which is what "offers BOTH" means here.
    await tester.tap(find.text('رفع صورة'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(OutlinedButton, 'اختيار صورة'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, 'التقاط صورة'), findsOneWidget);
  });

  testWidgets(
    'Back is disabled — liveness is done and there is nothing to return to',
    (tester) async {
      await pumpStage11(tester);

      // customer.md Stage 11 Exits: "Back → disabled."
      expect(find.byType(BackButton), findsNothing);
    },
  );

  testWidgets('there is no skip — the customer cannot proceed without signing', (
    tester,
  ) async {
    await pumpStage11(tester);

    // "There is no skip. A customer who can neither draw nor upload cannot complete the journey."
    final next = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'التالي'),
    );
    expect(next.onPressed, isNull);
  });

  testWidgets('a drawn signature submits as PNG with captureMethod "drawn"', (
    tester,
  ) async {
    final h = await pumpStage11(tester);
    await draw(tester);

    await submit(tester);

    expect(h.api.callCount, 1);
    // The wire values SignatureService allows, fixed per route rather than sniffed.
    expect(h.api.lastSignature!.method, SignatureCaptureMethod.drawn);
    expect(h.api.lastSignature!.contentType, 'image/png');
    expect(h.api.lastSignature!.bytes, isNotEmpty);
    expect(h.landedPath(), '/stage-12');
  });

  testWidgets('a drawn signature is nowhere near the backend ceiling', (
    tester,
  ) async {
    // The band between SignatureService's 5 MB (coded SIGNATURE_REJECTED) and
    // SignatureController's ~5.72 MB base64 cap (a BARE, uncoded 400) is the defect this slice
    // routes around rather than fixing. Bounding the export keeps it unreachable.
    final h = await pumpStage11(tester);
    await draw(tester);

    await submit(tester);

    expect(
      h.api.lastSignature!.bytes.length,
      lessThan(backendSignatureMaxBytes),
    );
  });

  testWidgets('clear-and-retry empties the pad and re-disables Next', (
    tester,
  ) async {
    await pumpStage11(tester);
    await draw(tester);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, 'التالي'))
          .onPressed,
      isNotNull,
    );

    await tester.tap(find.widgetWithText(TextButton, 'مسح والإعادة'));
    await tester.pumpAndSettle();

    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, 'التالي'))
          .onPressed,
      isNull,
    );
  });

  testWidgets(
    'SIGNATURE_REJECTED is honest and generic — the backend does not say which cause',
    (tester) async {
      final h = await pumpStage11(tester);
      h.api.errorToThrow = const SignatureRejectedException();
      await draw(tester);

      await submit(tester);

      // One code covers all four causes (wrong method, wrong type, empty, too large), so the copy
      // must not claim to know which. It must also not tell a human to sign more simply.
      expect(find.textContaining('تعذر قبول هذا التوقيع'), findsOneWidget);
      expect(find.textContaining('التالي'), findsWidgets);
    },
  );

  testWidgets('LIVENESS_REQUIRED sends the customer back to Stage 10', (
    tester,
  ) async {
    final h = await pumpStage11(tester);
    h.api.errorToThrow = const JourneyConflictException(
      JourneyCode.livenessRequired,
    );
    await draw(tester);

    await submit(tester);

    // The backend, not this screen, is the authority on whether Stage 10 passed.
    expect(h.landedPath(), '/stage-10');
  });

  testWidgets(
    'PROFILE_TERMINAL ends the journey; anything else re-syncs through the pointer',
    (tester) async {
      final terminal = await pumpStage11(tester);
      terminal.api.errorToThrow = const JourneyConflictException(
        JourneyCode.profileTerminal,
      );
      await draw(tester);
      await submit(tester);
      expect(terminal.landedPath(), '/ended');

      final resync = await pumpStage11(tester);
      resync.api.errorToThrow = const JourneyConflictException(
        JourneyCode.stateConflict,
      );
      await draw(tester);
      await submit(tester);
      expect(resync.landedPath(), '/final-stages');
    },
  );

  testWidgets(
    'an unreachable backend keeps the capture on screen and offers a retry',
    (tester) async {
      final h = await pumpStage11(tester);
      h.api.errorToThrow = const BackendUnreachableException('offline');
      await draw(tester);

      await submit(tester);

      expect(find.textContaining('تعذر الاتصال'), findsOneWidget);
      // Not discarded — the customer must not have to sign again because the network dropped.
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, 'التالي'))
            .onPressed,
        isNotNull,
      );
    },
  );

  testWidgets(
    'BL-115 — the screen tells a customer who can neither draw nor upload where to go',
    (tester) async {
      // Stage 11 disables its submit until there is a capture, and customer.md disables Back at
      // this stage, so a customer who can do neither previously sat on a screen with no usable
      // control and no advice at all. Nothing here is spent and nothing expires — the reported
      // "wastes a single-use account" harm class was refuted at S8-12 — so the whole fix is the
      // one sentence the screen already owed them.
      await pumpStage11(tester);

      expect(
        find.text(
          'إذا تعذر عليك التوقيع على الشاشة أو رفع صورة، يرجى زيارة أقرب فرع.',
        ),
        findsOneWidget,
      );
    },
  );
}
