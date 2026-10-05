-- S2-04: the audit seal export. A new role, distinct from fru_app and fru_migrator, writes
-- the seal — the same separation-of-duties reasoning AD-005 report §5 Layer 1 applies to the
-- chain itself: if the role that writes audit events could also reseal the chain, a single
-- compromised fru_app credential could tamper the trail and cover its tracks by producing a
-- new seal over the tampered state. See docs/sessions/2026-08-27-s2-04-audit-seal.md.
CREATE ROLE fru_sealer LOGIN PASSWORD '${fru_sealer_password}';
