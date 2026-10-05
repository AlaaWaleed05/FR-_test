"""Restate every sizing table as actual need, factor applied, figure to provision."""
import sys

PATH = sys.argv[1]
text = open(PATH, encoding="utf8").read()

PAIRS = [
    # ---------------------------------------------------------------- 3.1
    ("""Every figure in sections 4, 5 and 6 is stated twice: a minimum, below which the system should not be
deployed, and a provisioning figure, which is what Az Technology asks the bank to allocate.

The provisioning figures exceed the measured requirement by roughly a factor of two. That margin is
chosen rather than assumed, for three reasons:""",
     """Every hardware figure in sections 4, 5 and 6 is stated three ways: the **actual need**, which is
either measured on the proven deployment or calculated from the capacity basis in 6.2; the **factor**
applied to it; and the figure Az Technology asks the bank to **provision**. Nothing is asked for
without the arithmetic behind it being visible.

The factors sit between two and five. They are chosen rather than assumed, for three reasons:"""),

    ("""Where a measured figure exists from the proven deployment, it is stated alongside the requirement so
that the bank can see the margin it is being asked to provide rather than having to infer it.""",
     """Two notes on reading the factors. Where the actual need is a small absolute number, a modest
absolute margin produces a large-looking factor: one vCPU doubled twice is still only four vCPU. And
where the chosen figure lands on a standard virtual machine size, the factor is a consequence of that
size rather than the reason for it. Both cases are marked in the tables.

Nothing in sections 4, 5 and 6 is sized for growth beyond the campaign described in 6.2. If the bank
later extends the solution to other customer populations, the database figures are the ones to
revisit first."""),

    # ---------------------------------------------------------------- SRV-APP
    ("""| Resource | Minimum | **Provision this** | Basis |
|---|---|---|---|
| vCPU | 2 | **4** | x86-64 architecture. The container image is built and tested for x86-64. ARM is not tested |
| RAM | 4 GB | **8 GB** | The JVM takes 75% of the host or container memory limit. The printed customer form is rendered in memory with embedded document images |
| Disk | 50 GB | **100 GB** | Operating system, container images, application logs. No customer data is stored here |
| Disk type | SSD | SSD | |

Measured need at the load in 6.2 is approximately 1 vCPU and 2 GB, which is what the proven
deployment runs. The provisioning figures are double that. The margin covers campaign-day peaks,
the memory cost of rendering the printed form, and the bank's own monitoring and endpoint agents.""",
     """| Resource | Actual need | Factor | **Provision this** |
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

No customer data is stored on this server."""),

    # ---------------------------------------------------------------- SRV-WEB
    ("""| Resource | Minimum | **Provision this** | Basis |
|---|---|---|---|
| vCPU | 1 | **2** | |
| RAM | 2 GB | **4 GB** | |
| Disk | 20 GB | **40 GB** | The operator interface is 1.7 MB of static files. The rest is operating system and logs |

This server holds no data and runs no application. The work itself needs a fraction of the figures
above; what they are sized for is the operating system, the bank's monitoring and endpoint-protection
agents, and log retention. There is no case for making it larger.""",
     """| Resource | Actual need | Factor | **Provision this** |
|---|---|---|---|
| vCPU | below 1 | smallest sensible size | **2** |
| RAM | below 1 GB | smallest sensible size | **4 GB** |
| Disk | 15 GB, calculated | ×2.5 | **40 GB** |

This server holds no data and runs no application. It serves 1.7 MB of static files and forwards
operator API traffic to SRV-APP, for a user population of bank staff rather than customers.

The processor and memory figures are not derived from a factor, because the work does not need a
whole core. They are the smallest configuration worth allocating once the operating system, the
bank's monitoring and endpoint-protection agents and log retention are accounted for. There is no
case for making this server larger."""),

    # ---------------------------------------------------------------- SRV-DB
    ("""| Resource | Minimum | **Provision this** |
|---|---|---|
| vCPU | 2 | **4** |
| RAM | 8 GB | **16 GB** |
| Data volume | 1 TB SSD, encrypted | **1.5 TB SSD, encrypted** |""",
     """| Resource | Actual need | Factor | **Provision this** |
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
dominated by large image values."""),
]

for old, new in PAIRS:
    if old not in text:
        raise SystemExit("NOT FOUND:\n" + old[:200])
    text = text.replace(old, new, 1)

open(PATH, "w", encoding="utf8").write(text)
print("updated", PATH)
