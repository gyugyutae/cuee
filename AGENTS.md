# CUEE repository instructions

Before planning or making any code, build-script, or test change in this repository, read `.kiro/steering/ponytail.md` in full and follow it as an always-on engineering rule.

In particular, preserve its CUEE safety rails: this product guides user taps; it must never automate login, train selection, booking, personal information, final confirmation, or payment, and it must never persist raw screen text or raw voice transcripts.

When instructions conflict, follow the higher-priority user or system instruction and explicitly call out the conflict.

For code simplification reviews, read `.codex/skills/ponytail-review/SKILL.md`.
It lists deletion candidates only. Apply fixes separately when requested,
keeping the CUEE safety rails and runnable checks above.
