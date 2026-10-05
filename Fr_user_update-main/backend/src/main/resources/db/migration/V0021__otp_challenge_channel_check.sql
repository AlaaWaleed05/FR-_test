-- app.otp_challenge.channel had no CHECK constraint, unlike its sibling
-- app.profile_channel.channel (V0007), so a mistyped channel value inserted silently.
-- Flagged by @agent-reviewer in S2-02 §10 and correctly left then, since the AD-005 report's
-- own DDL has the same gap and CLAUDE.md's rule is to follow the report's DDL, not redesign
-- it in passing. Closed now, byte-identical value set to app.profile_channel's constraint.
ALTER TABLE app.otp_challenge
  ADD CONSTRAINT otp_challenge_channel_check CHECK (channel IN ('sms', 'whatsapp', 'email'));
