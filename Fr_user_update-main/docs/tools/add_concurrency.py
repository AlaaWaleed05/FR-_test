"""Add the concurrency statement (AP-7) and the SMS gateway question (BR-13)."""
import sys

PATH = sys.argv[1]
text = open(PATH, encoding="utf8").read()

AP6 = """**AP-6. Health check.** `GET /actuator/health` returns the service state and is the endpoint a load
balancer or monitoring system should poll. It reports DOWN when the database is unreachable, which
is intended."""

AP7 = AP6 + """

**AP-7. Concurrent capacity.** At the design rate, 17 to 25 customers are part-way through a journey
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

BR12 = ("| BR-12 | How the container image reaches the bank: a registry to pull from, or an offline "
        "archive | Decide | AP-1 |")

BR13 = BR12 + ("\n| BR-13 | The SMS gateway's per-message latency and any rate limit applied to the "
               "bank's account | Supply | NW-2A, AP-7. Sets the ceiling on one-time code delivery |")

for old, new in ((AP6, AP7), (BR12, BR13)):
    if old not in text:
        raise SystemExit("NOT FOUND:\n" + old[:120])
    text = text.replace(old, new, 1)

open(PATH, "w", encoding="utf8").write(text)
print("updated", PATH)
