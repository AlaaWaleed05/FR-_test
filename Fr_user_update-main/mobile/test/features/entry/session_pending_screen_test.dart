import 'package:drift/drift.dart' hide isNull;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/features/entry/session_pending_screen.dart';

void main() {
  Future<SessionDatabase> seedProgress() async {
    final db = SessionDatabase.forTesting();
    await db
        .into(db.localDraft)
        .insertOnConflictUpdate(
          LocalDraftCompanion.insert(id: const Value(0), updatedAt: DateTime.now()),
        );
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '2',
            verifiedAccountNumber: '12345',
            resumeStage: 'verified',
            profileId: const Value('p1'),
            channelsSummary: const Value('sms:verified:••••1234'),
            updatedAt: DateTime.now(),
          ),
        );
    return db;
  }

  testWidgets('renders the cached verified-channel summary from LocalProgress', (tester) async {
    final db = await seedProgress();
    addTearDown(db.close);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sessionDatabaseProvider.overrideWithValue(db)],
        child: MaterialApp(
          home: Directionality(
            textDirection: TextDirection.rtl,
            child: SessionPendingScreen(),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('••••1234'), findsOneWidget);
  });

  testWidgets(
    'only verified channels are shown — a declined channel, and a channel that was never '
    'proven, are both absent (repurposed from F7, S5-02, at S5-04)',
    (tester) async {
      final db = SessionDatabase.forTesting();
      addTearDown(db.close);
      await db
          .into(db.localProgress)
          .insertOnConflictUpdate(
            LocalProgressCompanion.insert(
              id: const Value(0),
              verifiedBranchCode: '2',
              verifiedAccountNumber: '12345',
              resumeStage: 'verified',
              profileId: const Value('p1'),
              channelsSummary: const Value(
                'sms:verified:••••1234|whatsapp:declined:••••1234|email:unverified:a•••@x.com',
              ),
              updatedAt: DateTime.now(),
            ),
          );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [sessionDatabaseProvider.overrideWithValue(db)],
          child: MaterialApp(
            home: Directionality(
              textDirection: TextDirection.rtl,
              child: SessionPendingScreen(),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('واتساب'), findsNothing);
      expect(find.textContaining('البريد الإلكتروني'), findsNothing);
      expect(find.textContaining('الرسائل النصية'), findsOneWidget);
    },
  );

  testWidgets('Abandon clears local state and returns to /account-entry', (tester) async {
    final db = await seedProgress();
    addTearDown(db.close);

    var landedOnAccountEntry = false;
    final router = GoRouter(
      initialLocation: '/session-pending',
      routes: [
        GoRoute(
          path: '/session-pending',
          builder: (context, state) => const SessionPendingScreen(),
        ),
        GoRoute(
          path: '/account-entry',
          builder: (context, state) {
            landedOnAccountEntry = true;
            return const Scaffold(body: Text('account entry'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sessionDatabaseProvider.overrideWithValue(db)],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('التراجع عن الجلسة'));
    await tester.pumpAndSettle();

    expect(landedOnAccountEntry, isTrue);
    expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
    expect(await (db.select(db.localDraft)).getSingleOrNull(), isNull);
  });
}
