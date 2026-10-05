import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'router/app_router.dart';
import 'theme/app_theme.dart';

/// The app shell. RTL is forced EXPLICITLY via the `builder`-level [Directionality] wrap, not
/// left to locale inference — the task's explicit requirement ("the app's own text direction
/// set rather than inherited by accident"), even though `Locale('ar')` alone would already
/// resolve RTL through `Localizations`.
class FruApp extends ConsumerWidget {
  const FruApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(goRouterProvider);
    return MaterialApp.router(
      routerConfig: router,
      locale: const Locale('ar'),
      supportedLocales: const [Locale('ar')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      builder: (context, child) =>
          Directionality(textDirection: TextDirection.rtl, child: child!),
      theme: AppTheme.light(),
    );
  }
}
