"""Sizing option C: design rate of 100 submissions per hour, factor of two throughout."""
import sys

SRC, OUT = sys.argv[1], sys.argv[2]
text = open(SRC, encoding="utf8").read()

PAIRS = [
    # ---------------------------------------------------------------- front matter
    ("| **Revision** | 0.1, sizing option B, measured plus margin. Draft for internal review. "
     "Not issued |",
     "| **Revision** | 0.1, sizing option C. Design rate 100 submissions per hour, factor of two. "
     "Draft for internal review. Not issued |"),

    # ---------------------------------------------------------------- 3.1
    ("""The factors sit between two and five. They are chosen rather than assumed, for three reasons:""",
     """**A factor of two is applied to every figure**, with no exceptions and no per-resource
judgement. The margin exists for three reasons:"""),

    ("""Two notes on reading the factors. Where the actual need is a small absolute number, a modest
absolute margin produces a large-looking factor: one vCPU doubled twice is still only four vCPU. And
where the chosen figure lands on a standard virtual machine size, the factor is a consequence of that
size rather than the reason for it. Both cases are marked in the tables.

Nothing in sections 4, 5 and 6 is sized for growth beyond the campaign described in 6.2. If the bank
later extends the solution to other customer populations, the database figures are the ones to
revisit first.""",
     """**One caveat, stated plainly because it affects how much weight these numbers carry.** The
proven deployment runs at about three submissions per hour, which leaves it close to idle. Its
resource consumption is therefore evidence that the software runs, not a measurement of its
throughput, and it cannot be scaled up by arithmetic. The actual-need figures for the design rate in
this document are **calculated from the work each submission performs**, not measured at that rate.
Az Technology recommends a load test at the design rate before go-live, and will treat any correction
it produces as a revision to this document rather than a change request.

Nothing in sections 4, 5 and 6 is sized for growth beyond the campaign described in 6.2. If the bank
later extends the solution to other customer populations, the database figures are the ones to
revisit first."""),

    # ---------------------------------------------------------------- SRV-APP
    ("""| Resource | Actual need | Factor | **Provision this** |
|---|---|---|---|
| vCPU | 1, measured | ×4 | **4** |
| RAM | 2 GB, measured | ×4 | **8 GB** |
| Disk | 25 GB, calculated | ×4 | **100 GB** |
| Disk type | SSD | | **SSD** |

The measured figures are what the proven deployment runs today at the load described in 6.2: one
vCPU and 2 GB, carrying a full customer journey end to end. The calculated disk need is the
operating system, two or three retained container images at about 250 MB each, and roughly a year
of application logs.

Three notes on the factors:

- **The vCPU factor is large because the number is small.** Load is not evenly spread: an SMS
  campaign batch delivers a burst of customers within the hour, and rendering the printed customer
  form is the most processor-intensive thing the application does. Four vCPU is the smallest figure
  that absorbs that comfortably.
- **8 GB rather than 4 GB is a standard-size decision.** Doubling the measured 2 GB gives 4 GB, but
  the JVM is capped at 75% of the memory limit, which would leave a 3 GB heap to render a form
  holding several megabytes of images. 8 GB is the next standard virtual machine size and removes
  the question.
- **x86-64 architecture.** The container image is built and tested for x86-64. ARM is not tested.

No customer data is stored on this server.""",
     """| Resource | Actual need at 100/hour | Factor | **Provision this** |
|---|---|---|---|
| vCPU | 2, calculated | ×2 | **4** |
| RAM | 4 GB, calculated | ×2 | **8 GB** |
| Disk | 40 GB, calculated | ×2 | **80 GB** |
| Disk type | SSD | | **SSD** |

At 100 submissions per hour one customer arrives every 36 seconds, and a journey spans several
minutes, so roughly 20 to 30 customers are part-way through one at any moment. Each completed
journey moves about 4.8 MB of document images through the application and into the database.

How the three figures are arrived at:

- **2 vCPU.** The work per submission is image handling, signature verification of the scan result
  and database writes. None of it is long-running, and at this rate it does not saturate a single
  core. The second core covers garbage collection and the printed customer form, which operators
  render in parallel with customer traffic and which is the most processor-intensive thing the
  application does.
- **4 GB.** The JVM is capped at 75% of the memory limit, so 4 GB yields a 3 GB heap. Concurrent
  image handling and form rendering are what consume it.
- **40 GB.** The operating system, two or three retained container images at about 250 MB each, and
  roughly a year of application logs at this request rate.

**x86-64 architecture.** The container image is built and tested for x86-64. ARM is not tested. No
customer data is stored on this server."""),

    # ---------------------------------------------------------------- SRV-WEB
    ("""| Resource | Actual need | Factor | **Provision this** |
|---|---|---|---|
| vCPU | below 1 | smallest sensible size | **2** |
| RAM | below 1 GB | smallest sensible size | **4 GB** |
| Disk | 15 GB, calculated | ×2.5 | **40 GB** |

This server holds no data and runs no application. It serves 1.7 MB of static files and forwards
operator API traffic to SRV-APP, for a user population of bank staff rather than customers.

The processor and memory figures are not derived from a factor, because the work does not need a
whole core. They are the smallest configuration worth allocating once the operating system, the
bank's monitoring and endpoint-protection agents and log retention are accounted for. There is no
case for making this server larger.""",
     """| Resource | Actual need at 100/hour | Factor | **Provision this** |
|---|---|---|---|
| vCPU | 1, the smallest allocatable unit | ×2 | **2** |
| RAM | 2 GB, calculated | ×2 | **4 GB** |
| Disk | 20 GB, calculated | ×2 | **40 GB** |

This server holds no data and runs no application. It serves 1.7 MB of static files and forwards
operator API traffic to SRV-APP.

The design rate does reach this server, but indirectly: 100 submissions an hour arriving for review
means more operators signed in at once, not more work per operator. Even so, serving static files and
proxying their API calls does not need a whole processor core. The actual-need figures are therefore
what the operating system, nginx, the bank's monitoring and endpoint-protection agents and log
retention require, and 1 vCPU is stated because it is the smallest unit that can be allocated rather
than because the work demands it."""),

    # ---------------------------------------------------------------- SRV-DB
    ("""| Resource | Actual need | Factor | **Provision this** |
|---|---|---|---|
| vCPU | 2, calculated | ×2 | **4** |
| RAM | 8 GB, calculated | ×2 | **16 GB** |
| Data volume | 500 GB live at full campaign | ×3 | **1.5 TB SSD, encrypted** |

The storage factor is the largest on this specification and it is the one least worth arguing over.
It covers the 500 GB of live data at the end of the campaign, local backup copies of a database in
which every row carries identity document images, and the fact that this volume cannot be practically
resized afterwards: encryption is configured when the volume is created, and re-creating it means
moving hundreds of gigabytes of identity documents.

The processor and memory figures carry a factor of two over the calculated need. The write load is
three submissions an hour, so the database is not processor-bound; the memory is sized so that
PostgreSQL's buffers and the operating system's file cache can hold the working set of a store
dominated by large image values.""",
     """| Resource | Actual need at 100/hour | Factor | **Provision this** |
|---|---|---|---|
| vCPU | 2, calculated | ×2 | **4** |
| RAM | 8 GB, calculated | ×2 | **16 GB** |
| Data volume | 1 TB, calculated | ×2 | **2 TB SSD, encrypted** |

**The data volume is the one figure the design rate does not change.** Storage is set by the number
of customer accounts, not by how quickly they arrive. Raising the rate shortens the campaign; it does
not enlarge the database. The 1 TB of actual need is the 350 to 500 GB of live data at the end of the
campaign plus local backup copies of a database in which every row carries identity document images.

The factor of two matters more here than anywhere else in this document, for a reason that is not
about capacity: this volume cannot be practically resized afterwards. Encryption is configured when
the volume is created, and re-creating it means moving hundreds of gigabytes of identity documents
under a maintenance window.

At 100 submissions an hour the database absorbs about 480 MB per hour, roughly 140 KB per second
sustained, in bursts of several megabytes as each submission completes. That is not a demanding write
load and the workload is not processor-bound. The memory is sized so that PostgreSQL's buffers and
the operating system's file cache hold the working set of a store whose bulk is large image values
that are written once and read rarely."""),

    # ---------------------------------------------------------------- 6.2 capacity
    ("""| Campaign size | About 100,000 customer accounts, one submission each, over 12 to 18 months |
| Average write load | About 3 submissions per hour |
| Data per completed profile | About 4.8 MB, including identity document images |
| Live data at full campaign | 350 to 500 GB |
| Working storage required, including local backup copies | 1 to 1.5 TB |
| **Storage to provision** | **1.5 TB**, covering live data, local backup copies and growth beyond the campaign |
| Audit rows | 6 to 10 million |
| Table partitioning | Not required |""",
     """| Campaign size | About 100,000 customer accounts, one submission each |
| **Design submission rate** | **100 submissions per hour** |
| Campaign duration at that rate | About 1,000 operating hours |
| Data per completed profile | About 4.8 MB, including identity document images |
| Sustained write throughput | About 480 MB per hour, roughly 140 KB per second |
| Live data at full campaign | 350 to 500 GB |
| Working storage required, including local backup copies | About 1 TB |
| **Storage to provision** | **2 TB** |
| Audit rows | 6 to 10 million |
| Table partitioning | Not required |

The design rate is stated as a requirement rather than derived from the campaign length. It is what
sections 4 and 6 are sized against. Two consequences worth separating, because they are commonly
conflated: the rate determines **processor, memory and message throughput**, and the number of
accounts determines **storage**. Raising the rate again would not require a larger database volume."""),

    # ---------------------------------------------------------------- AP-3, AP-5
    ("""**AP-3. One instance only.** The operator session is held in the application's own memory. There is
no external session store. Running two instances without session affinity signs operators out at
random.""",
     """**AP-3. One instance only.** The operator session is held in the application's own memory. There is
no external session store. Running two instances without session affinity signs operators out at
random.

At the design rate this constraint deserves stating in operational terms: roughly 20 to 30 customers
are part-way through a journey at any moment, and there is no second instance to absorb a restart of
this one. Customer progress is persisted, so an interrupted customer can resume rather than start
again, but the interruption is real. Restarts and patching belong in a maintenance window."""),

    ("""**AP-5. Autoscaling is not required.** Expected load is about three customer submissions per hour.
The only anticipated spike follows an SMS campaign sent by the bank, which is a scheduled event.""",
     """**AP-5. Autoscaling is not required.** Sections 4.1 and 6.1 are sized for a design rate of 100
customer submissions per hour with a factor of two, which is the capacity ceiling this specification
buys. Load above it would follow an SMS campaign batch sent by the bank. That is a scheduled event,
so the response is to plan the batch sizes against the design rate rather than to react to the
consequences of one."""),

    # ---------------------------------------------------------------- message throughput
    ("""### 7.2 Hostnames and certificates""",
     """**NW-2A. Outbound message throughput.** The journey sends up to three one-time codes at channel
verification, one submission notification, and one message on every later status change: about five
messages per customer. At the design rate that is **up to 500 messages per hour**, and about 500,000
across the campaign. The SMS contract, and any rate limit the gateway applies to the bank's account,
must be sized against those figures rather than against an average. A gateway that throttles at this
rate does not fail loudly; it delays one-time codes, and a delayed one-time code is an abandoned
customer.

### 7.2 Hostnames and certificates"""),
]

for old, new in PAIRS:
    if old not in text:
        raise SystemExit("NOT FOUND:\n" + old[:200])
    text = text.replace(old, new, 1)

open(OUT, "w", encoding="utf8").write(text)
print("wrote", OUT)
