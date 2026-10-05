"""Derive the measured-plus-margin sizing variant from the headroom variant."""
import sys

SRC, OUT = sys.argv[1], sys.argv[2]
text = open(SRC, encoding="utf8").read()

PAIRS = [
    # ---------------------------------------------------------------- front matter
    ("| **Revision** | 0.1, draft for internal review. Not issued |",
     "| **Revision** | 0.1, sizing option B, measured plus margin. Draft for internal review. "
     "Not issued |"),

    # ---------------------------------------------------------------- 3.1 rationale
    ("The provisioning figures deliberately exceed the calculated requirement, in most cases by a "
     "factor\nof two to four. That is not padding for its own sake. Three reasons:",
     "The provisioning figures exceed the measured requirement by roughly a factor of two. That "
     "margin is\nchosen rather than assumed, for three reasons:"),

    # ---------------------------------------------------------------- SRV-APP
    ("""| vCPU | 4 | **8** | x86-64 architecture. The container image is built and tested for x86-64. ARM is not tested |
| RAM | 8 GB | **16 GB** | The JVM takes 75% of the host or container memory limit. The printed customer form is rendered in memory with embedded document images |
| Disk | 100 GB | **200 GB** | Operating system, container images, application logs. No customer data is stored here |""",
     """| vCPU | 2 | **4** | x86-64 architecture. The container image is built and tested for x86-64. ARM is not tested |
| RAM | 4 GB | **8 GB** | The JVM takes 75% of the host or container memory limit. The printed customer form is rendered in memory with embedded document images |
| Disk | 50 GB | **100 GB** | Operating system, container images, application logs. No customer data is stored here |"""),

    ("Measured need at the load in 6.2 is approximately 1 vCPU and 2 GB, which is what the proven\n"
     "deployment runs. The figures above carry a deliberate safety factor over that. See section 3.1.",
     "Measured need at the load in 6.2 is approximately 1 vCPU and 2 GB, which is what the proven\n"
     "deployment runs. The provisioning figures are double that. The margin covers campaign-day "
     "peaks,\nthe memory cost of rendering the printed form, and the bank's own monitoring and "
     "endpoint agents."),

    # ---------------------------------------------------------------- SRV-WEB
    ("""| vCPU | 2 | **4** | |
| RAM | 4 GB | **8 GB** | |
| Disk | 40 GB | **100 GB** | The operator interface is 1.7 MB of static files. The rest is operating system and logs |""",
     """| vCPU | 1 | **2** | |
| RAM | 2 GB | **4 GB** | |
| Disk | 20 GB | **40 GB** | The operator interface is 1.7 MB of static files. The rest is operating system and logs |"""),

    ("This server holds no data and runs no application. The figures above are deliberately well "
     "above\nwhat the work requires, so that the bank can add logging, monitoring agents, endpoint "
     "protection\nand future operator tooling to it without revisiting the specification.",
     "This server holds no data and runs no application. The work itself needs a fraction of the "
     "figures\nabove; what they are sized for is the operating system, the bank's monitoring and "
     "endpoint-protection\nagents, and log retention. There is no case for making it larger."),

    # ---------------------------------------------------------------- SRV-DB
    ("""| vCPU | 4 | **8** |
| RAM | 16 GB | **32 GB** |
| Data volume | 2 TB SSD, encrypted | **3 TB SSD, encrypted** |""",
     """| vCPU | 2 | **4** |
| RAM | 8 GB | **16 GB** |
| Data volume | 1 TB SSD, encrypted | **1.5 TB SSD, encrypted** |"""),

    ("| **Storage to provision** | **2 to 3 TB**, carrying roughly double the calculated "
     "requirement |",
     "| **Storage to provision** | **1.5 TB**, covering live data, local backup copies and "
     "growth beyond the campaign |"),
]

for old, new in PAIRS:
    if old not in text:
        raise SystemExit("NOT FOUND:\n" + old[:160])
    text = text.replace(old, new, 1)

open(OUT, "w", encoding="utf8").write(text)
print("wrote", OUT)
