# github.md

repo: Osmantou/Fr_user_update
branch: main
path: mobile/

## Last sync

date: 2026-09-08T22:13:57Z
commit: d8f2d518acc1

### Updated in this project
- Dropped the dead `/session-pending` screen from the designs (unreachable since S5-08; its "later app version" copy no longer holds) and designed `/final-stages` in its place — resolving state plus its offline error.
- Designed all screens (`Bayanati Screens.dc.html`): every route in app_router.dart plus the offline, field-error, scan-failed and scan-blocked states, with Arabic copy lifted verbatim from the Dart screens.
- Rebuilt the component library around the real journey (one-time account data update) after the repo showed it is not a transactional banking app.
- Lifted real strings from source: channel labels, the offline banner, the provenance vocabulary, and the "submitted for review, never approved" rule for the terminal screens.
- Adopted the repo's numeral rules (Arabic-Indic typed → ASCII stored, Latin displayed, DD/MM/YYYY, direction-isolated values) instead of the rule the library previously invented.
- Re-derived the wordmark assets from docs/brand/sfb-logo-master-2048.jpg rather than the JPEG screenshot extraction.
- Resolved the brand-blue conflict toward the owner's deep-navy palette (#0b1c47 / #0c193a) over app_theme.dart's #105097.

## Screen map

| Design | Built from |
| --- | --- |
| Screens 01 launch | mobile/lib/features/entry/launch_screen.dart |
| Screens 02–03 account entry + errors | mobile/lib/features/entry/account_entry_screen.dart, offline_banner.dart |
| Screens 04–05 channels + verification | mobile/lib/features/entry/contact_channels_screen.dart, channel_verification_screen.dart, mobile/lib/core/entry/channel_labels.dart |
| Screens 06–10 stages 3–7 | mobile/lib/features/dataentry/stage3_screen.dart … stage7_screen.dart, address_cascade_fields.dart, salary_certificate_field.dart, reference_item_picker.dart |
| Screens 11–14 scan, failure, blocked, registry review | mobile/lib/features/identityscan/stage8_screen.dart, stage9_screen.dart, scan_blocked_view.dart |
| Screen 15 liveness | mobile/lib/features/liveness/stage10_screen.dart |
| Screen 16 signature | mobile/lib/features/signature/stage11_screen.dart |
| Screens 17–22 submit, confirmation, complete, terminal, resume gate | mobile/lib/features/submission/stage12_screen.dart, confirmation_screen.dart, session_complete_screen.dart, final_stages_gate_screen.dart, mobile/lib/features/entry/terminal_screen.dart |

| Design | Built from |
| --- | --- |
| Component library — stage header, progress, offline banner | mobile/lib/core/widgets/screen_title.dart, mobile/lib/features/entry/offline_banner.dart, mobile/lib/core/router/app_router.dart |
| Component library — type, color, surfaces | mobile/lib/core/theme/app_theme.dart, mobile/lib/core/app.dart, uploads/Color Paltted.pdf (owner palette) |
| Component library — fields, OTP, pickers | mobile/lib/core/forms/field_error_state.dart, mobile/lib/core/text/arabic_digit_input_formatter.dart, ltr_value.dart, display_date.dart, docs/journeys/field-provenance.md |
| Component library — channels | mobile/lib/core/entry/channel_labels.dart |
| Brand assets (assets/sfb-wordmark*.png) | docs/brand/sfb-logo-master-2048.jpg |
| Component library — capture surfaces | mobile/lib/features/identityscan/stage8_screen.dart, stage9_screen.dart, mobile/lib/features/liveness/stage10_screen.dart, mobile/lib/features/signature/stage11_screen.dart |
| Component library — terminal states | mobile/lib/features/submission/confirmation_screen.dart, session_complete_screen.dart, mobile/lib/features/entry/terminal_screen.dart, mobile/lib/features/identityscan/scan_blocked_view.dart |
| SFB Splash Screen.dc.html (Splash C approved) | docs/brand/, uploads (banner + circular logo) |
| SFB Mobile Banner.dc.html | uploads (banner screenshot) |

## Notes

- Stage routes: `/` launch → `/account-entry` → `/contact-channels` → `/channel-verification` → `/stage-3` … `/stage-7` → `/stage-8`, `/stage-9` (Uqudo document scan) → `/final-stages` gate → `/stage-10` liveness → `/stage-11` signature → `/stage-12` submit → `/confirmation` → `/session-complete`. Side routes: `/session-pending`, `/terminal`.
- Screen copy in the designs is illustrative Arabic; the real strings live in the Dart screens and should win on implementation.
