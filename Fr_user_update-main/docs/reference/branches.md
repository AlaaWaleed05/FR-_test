# Branch list

Supplied by the product owner, 2026-08-19. Reproduced as given.

Used by: customer journey stage 1a (branch selection), and passed to the Oracle stored
procedure `ProcessOmniCheckAct` together with the account number.

> **Corrected 2026-09-04 (AD-007, OQ-024):** branch is stored on the profile but is NOT sent
> to the core-banking check, which is an HTTPS/JSON `CheckAccount` call taking the account
> number only. The sentence above is kept as history.

| # | الفرع |
|---|---|
| 2 | بورتسودان |
| 3 | القضارف |
| 4 | الابيض |
| 5 | مدني |
| 6 | ام درمان |
| 7 | الدمازين |
| 8 | المناقل |
| 9 | سنار |
| 10 | نيالا |
| 11 | حلفا الجديده |
| 12 | السجانة |
| 13 | ربك |
| 14 | الحصاحيصا |
| 15 | سوق ليبيا |
| 16 | الخرطوم |
| 17 | الخرطوم ٢ |
| 18 | الخرطوم بحري |
| 19 | الجمهوريه |
| 20 | السوق المحلي |
| 21 | قاردن ستي |
| 22 | الرياض |
| 23 | عطبرة |
| 24 | المعمورة |
| 25 | الجنيد |
| 26 | الكدرو |

## Notes

**This list is definitive.** Confirmed by the product owner, 2026-08-19.

- 25 branches, numbered 2 to 26. There is no branch 1.
- Branch numbers are the identifier passed to the core banking system. The app displays
  the Arabic name; it sends the number.
