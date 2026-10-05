"""Reconcile the passages that still describe the old three-per-hour load."""
import sys

PATH = sys.argv[1]
text = open(PATH, encoding="utf8").read()

PAIRS = [
    ("""1. The campaign runs for 12 to 18 months and the data grows monotonically. The system is sized for
   its end state, not its opening week.""",
     """1. The data grows monotonically for the life of the campaign. The system is sized for its end
   state, not its opening week."""),

    ("""**DB-2.** Provision the full data volume at the outset rather than growing into it. The workload is
not I/O intensive: about three writes an hour, each carrying several megabytes of document images.""",
     """**DB-2.** Provision the full data volume at the outset rather than growing into it. The workload is
throughput-modest but write-heavy per transaction: at the design rate, about 100 submissions an hour,
each carrying several megabytes of document images, for a sustained 140 KB per second. General-purpose
SSD absorbs that comfortably. What it must not be is a volume that has to be extended mid-campaign."""),

    ("""**DB-3.** If the bank's virtualisation platform offers burstable or CPU-credit instance classes, do
not use one. The system runs far below its average for months and then peaks during a campaign,
which is exactly when a credit limit would be reached.""",
     """**DB-3.** If the bank's virtualisation platform offers burstable or CPU-credit instance classes, do
not use one. Load at the design rate is concentrated into the hours the bank runs the campaign and
falls to near nothing outside them. A credit-based class accumulates credit during the quiet hours
and would exhaust it during the busy ones, which is precisely the wrong way round. The failure is
also invisible until it happens, because nothing reports a credit balance unless somebody is watching
for it."""),
]

for old, new in PAIRS:
    if old not in text:
        raise SystemExit("NOT FOUND:\n" + old[:160])
    text = text.replace(old, new, 1)

open(PATH, "w", encoding="utf8").write(text)
print("reconciled", PATH)
