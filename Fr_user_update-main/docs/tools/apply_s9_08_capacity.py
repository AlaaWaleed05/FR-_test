"""S9-08: replace AP-7 with the MEASURED capability statement, and reconcile everything in the
document that depended on the guessed one.

Runs LAST in the sizing-c chain, after add_concurrency.py, because it rewrites the AP-7 block that
script inserts. See docs/tools/README.md for the full order.

    python docs/tools/apply_s9_08_capacity.py docs/bank-hosting-requirements-sizing-c.md

One-shot and not idempotent, like its siblings: every replacement raises if its anchor is missing.
"""
import sys

PATH = sys.argv[1]
text = open(PATH, encoding="utf8").read()

# ----------------------------------------------------------------- AP-5, the ceiling it promises
AP5_OLD = """**AP-5. Autoscaling is not required.** Sections 4.1 and 6.1 are sized for a design rate of 100
customer submissions per hour with a factor of two, which is the capacity ceiling this specification
buys. Load above it would follow an SMS campaign batch sent by the bank. That is a scheduled event,
so the response is to plan the batch sizes against the design rate rather than to react to the
consequences of one."""

AP5_NEW = """**AP-5. Autoscaling is not required.** Sections 4.1 and 6.1 are sized for a design rate of 100
customer submissions per hour with a factor of two. The measured capability in AP-7 is well above
that, and the limit is not the server: adding processor or memory beyond section 4.1 would not raise
it. Load above the design rate would follow an SMS campaign batch sent by the bank. That is a
scheduled event, so the response is to plan batch sizes against the declared rate in AP-7 rather
than to react to the consequences of one."""

# ----------------------------------------------------------------------------- AP-7, rewritten
AP7_OLD = """**AP-7. Concurrent capacity.** At the design rate, 17 to 25 customers are part-way through a journey
at any moment. Most of that time is the customer reading, typing or scanning rather than the server
working, so the application sees roughly one request per second, and perhaps five at a peak. The
server specified in 4.1 holds considerably more than that.

| Limit reached first | Capacity | What happens at the limit |
|---|---|---|
| Database connections held concurrently | 10 simultaneous operations | Further requests wait, then fail after 30 seconds |
| Application worker threads | 200 requests in flight | Further requests queue |
| Outbound message dispatch | Messages are sent one after another, so the ceiling is 3,600 divided by the gateway's per-message latency | One-time codes are delayed rather than lost |

Estimated ceiling on the hardware in 4.1 is **500 to 1,500 submissions per hour**, five to fifteen
times the design rate. The range is wide because it depends on how long one database operation holds
a connection while writing 4.8 MB of document images, which has not been measured at this rate.

**One consequence the bank should take from this table.** Every limit in it belongs to the
application's configuration, not to the server. Allocating more processor or memory than section 4.1
asks for would not raise the ceiling. If a rate above the design rate is ever required, the change
is ours to make, not a hardware purchase."""

AP7_NEW = """**AP-7. Concurrent capacity.** At the design rate, 20 to 30 customers are part-way through a
journey at any moment. Most of that time is the customer reading, typing or scanning rather than the
server working, so the application sees roughly one request per second, and perhaps five at a peak.

**What "capacity" means here, because the distinction decides who can change it.** Two different
figures are given below, and they are different in kind.

The first is the **capability of the application itself**: the rate at which it accepts, stores,
audits and hands on a completed submission, measured with every externally contracted service
treated as an input rather than as part of the system. Az Technology is accountable for this number.

The second is the **end-to-end rate the bank will observe**, which adds the response time of the
services the bank contracts directly — above all the SMS gateway. That number is not ours to
improve without the bank's suppliers.

Both figures below are measured, not estimated. The measurement is repeatable on demand: it is a
single command in the delivered source, and re-running it is how any future change to these numbers
should be checked.

**1. The application's own capability: at least 5,000 submissions per hour.**

| Resource | Utilisation at 5,000 submissions per hour |
|---|---|
| Database connections (20 provisioned) | Under 1% — one submission's 4.8 MB of document images holds a connection for about 0.12 seconds |
| Application worker threads (100 provisioned) | About 13 requests per second |
| Outbound dispatch, excluding the gateway | About 16 milliseconds of the application's own work per message |

**This figure can be raised by configuration, not by hardware.** The application dispatches
outbound messages in batches on a timer; both the batch size and the timer are settings, and
widening them takes the same measurement above 14,000 submissions per hour on the same server.
Nothing in section 4.1 would need to change. We have not raised them, because the figure is already
far beyond the design rate and beyond the end-to-end limit below, and an unused margin is not worth
the risk of changing a setting nobody has needed.

**2. The binding constraint is the SMS gateway, and it belongs to the bank's contract.**

Every completed submission sends two SMS messages — a submission confirmation and a message on the
review decision — and one more at the start for the one-time code. The application sends them one
after another, so the end-to-end rate is governed by how quickly the gateway answers.

No per-message response time is published by the gateway operator, and no service level covering
throughput exists in anything supplied to us (BR-13 asks the bank for both and is unanswered). In
its absence we measured it: two live sends through the contracted gateway to a Sudanese handset took
**1.97 and 2.14 seconds**. At that response time the end-to-end rate is about **820 submissions per
hour**.

**3. Declared capability: 500 submissions per hour.**

That is the figure Az Technology will stand behind, and it is deliberately below the 820 measured.
The margin covers the two things the measurement cannot settle: only two live sends exist, both
taken at low load, and nobody has told us what the gateway does when several are in flight at once.
500 submissions per hour is five times the design rate in section 4.1.

**What would raise it, in order of cost.** Sending messages to the gateway concurrently instead of
one at a time would take the end-to-end figure to roughly 1,700 per hour. The application is already
built so this is a configuration-scale change rather than a redesign, and it is deliberately not
enabled: sending several requests at once to an account whose rate limit nobody has published is how
an account gets throttled, and a throttled SMS route stops one-time codes for every customer. The
three questions in BR-13 are what unblocks it.

**One consequence the bank should take from this.** Every limit above belongs to the application's
configuration or to the bank's own gateway contract — none of them to the server. Allocating more
processor or memory than section 4.1 asks for would not raise any of these numbers."""

# --------------------------------------------------------------------------- NW-2A, the SMS volume
NW2A_OLD = """**NW-2A. Outbound message throughput.** The journey sends up to three one-time codes at channel
verification, one submission notification, and one message on every later status change: about five
messages per customer. At the design rate that is **up to 500 messages per hour**, and about 500,000
across the campaign. The SMS contract, and any rate limit the gateway applies to the bank's account,
must be sized against those figures rather than against an average. A gateway that throttles at this
rate does not fail loudly; it delays one-time codes, and a delayed one-time code is an abandoned
customer."""

NW2A_NEW = """**NW-2A. Outbound message throughput.** The release version sends on one channel, SMS. A customer
receives **three messages**: a one-time code at the start, a confirmation when the profile is
submitted, and one when it is approved or rejected. A customer who asks for the code to be resent,
or whose profile is rejected and resubmitted, receives more.

At the design rate that is **about 300 messages per hour**, and in the region of 300,000 across the
campaign. The SMS contract should be sized against those figures rather than against an average, and
with headroom for resends.

**An earlier draft of this section said five messages per customer and 500,000 across the campaign.
That was wrong** — it counted a one-time code on each of three channels, where only SMS is offered.
The corrected figure is materially lower, and the correction is stated rather than made silently
because the number sizes a purchase.

A gateway that throttles at this rate does not fail loudly; it delays one-time codes, and a delayed
one-time code is an abandoned customer. That is why BR-13 asks for the rate limit as well as the
response time."""

# ---------------------------------------------------------------------------------- BR-13 reworded
BR13_OLD = ("| BR-13 | The SMS gateway's per-message latency and any rate limit applied to the "
            "bank's account | Supply | NW-2A, AP-7. Sets the ceiling on one-time code delivery |")

BR13_NEW = ("| BR-13 | The SMS gateway's per-message response time, the maximum sending rate on the "
            "bank's account, and how many requests may be in flight at once | Supply | **NW-2A, "
            "AP-7. This is the single input that sets the end-to-end capacity in AP-7.** We have "
            "measured the response time ourselves over two sends; the rate limit and the "
            "concurrency allowance are unpublished and only the operator can answer them |")

# ------------------------------------------------------- the summary table gains the declared rate
SUMMARY_OLD = "| **Design submission rate** | **100 submissions per hour** |"
SUMMARY_NEW = ("| **Design submission rate** | **100 submissions per hour** |\n"
               "| **Declared capability** | **500 submissions per hour** (AP-7; measured end-to-end "
               "figure about 820, application alone above 5,000) |")

for old, new in (
    (AP5_OLD, AP5_NEW),
    (AP7_OLD, AP7_NEW),
    (NW2A_OLD, NW2A_NEW),
    (BR13_OLD, BR13_NEW),
    (SUMMARY_OLD, SUMMARY_NEW),
):
    if old not in text:
        raise SystemExit("NOT FOUND:\n" + old[:160])
    text = text.replace(old, new, 1)

# The revision line, so the reader can tell this version from the one before it.
REV_OLD = ("| **Revision** | 0.1, sizing option C. Design rate 100 submissions per hour, factor of "
           "two. Draft for internal review. Not issued |")
REV_NEW = ("| **Revision** | 0.2, sizing option C. Design rate 100 submissions per hour, factor of "
           "two; capacity in AP-7 measured rather than estimated. Draft for internal review. Not "
           "issued |")
if REV_OLD not in text:
    raise SystemExit("NOT FOUND (revision line):\n" + REV_OLD[:160])
text = text.replace(REV_OLD, REV_NEW, 1)

open(PATH, "w", encoding="utf8").write(text)
print("applied S9-08 capacity corrections to", PATH)
