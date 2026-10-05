from PIL import Image, ImageDraw, ImageFont

W, H = 2400, 1820
RED = (228, 49, 42)
PURPLE = (66, 39, 116)
GREY = (120, 124, 130)
LINE = (90, 94, 100)
LIGHT = (246, 246, 248)
BORDER = (198, 201, 208)
WHITE = (255, 255, 255)
INK = (32, 34, 38)

F = "C:/Windows/Fonts/"
def f(name, size):
    return ImageFont.truetype(F + name, size)

TITLE = f("segoeuib.ttf", 40)
BOXT = f("segoeuib.ttf", 38)
BOXS = f("segoeui.ttf", 31)
LBL = f("segoeui.ttf", 29)
LBLB = f("segoeuib.ttf", 29)
ZONE = f("segoeuib.ttf", 33)
NOTE = f("segoeui.ttf", 28)

im = Image.new("RGB", (W, H), WHITE)
d = ImageDraw.Draw(im)


def box(x1, y1, x2, y2, title, lines, accent=PURPLE, fill=WHITE):
    d.rounded_rectangle([x1, y1, x2, y2], 14, fill=fill, outline=BORDER, width=3)
    d.rounded_rectangle([x1, y1, x2, y1 + 66], 14, fill=accent)
    d.rectangle([x1, y1 + 46, x2, y1 + 66], fill=accent)
    d.text((x1 + 26, y1 + 14), title, font=BOXT, fill=WHITE)
    y = y1 + 92
    for ln, style in lines:
        d.text((x1 + 26, y), ln, font=style, fill=INK if style is BOXS else GREY)
        y += 44


def arrow(x1, y1, x2, y2, label=None, lx=0, ly=0, colour=LINE, dashed=False):
    if dashed:
        n = max(abs(x2 - x1), abs(y2 - y1)) // 26
        for i in range(int(n)):
            if i % 2:
                continue
            a = i / n
            b = min((i + 1) / n, 1)
            d.line([x1 + (x2 - x1) * a, y1 + (y2 - y1) * a,
                    x1 + (x2 - x1) * b, y1 + (y2 - y1) * b], fill=colour, width=4)
    else:
        d.line([x1, y1, x2, y2], fill=colour, width=4)
    s = 17
    if x1 == x2:
        dy = s if y2 > y1 else -s
        d.polygon([(x2, y2), (x2 - s, y2 - dy), (x2 + s, y2 - dy)], fill=colour)
    else:
        dx = s if x2 > x1 else -s
        d.polygon([(x2, y2), (x2 - dx, y2 - s), (x2 - dx, y2 + s)], fill=colour)
    if label:
        d.text((lx, ly), label, font=LBL, fill=GREY)


# ---------------------------------------------------------------- internet zone
d.rounded_rectangle([60, 40, W - 60, 300], 16, fill=LIGHT, outline=BORDER, width=3)
d.text((90, 60), "PUBLIC INTERNET", font=ZONE, fill=RED)

box(110, 120, 700, 270, "Customer mobile app",
    [("Android, Google Play", BOXS)], accent=RED)

box(1500, 120, 2290, 270, "Uqudo eKYC  ·  SMS gateway",
    [("Reached outbound from SRV-APP on 443", BOXS)], accent=GREY)

# ---------------------------------------------------------------- firewall
d.rounded_rectangle([60, 360, W - 60, 470], 14, fill=(252, 236, 235), outline=RED, width=4)
d.text((100, 385), "BANK FIREWALL", font=ZONE, fill=RED)
d.text((470, 392), "Publishes port 443 to SRV-APP only.  Blocks every other inbound path.",
       font=LBL, fill=INK)

arrow(405, 270, 405, 355, "HTTPS 443", 425, 295)

# ---------------------------------------------------------------- bank network
d.rounded_rectangle([60, 530, W - 60, 1660], 18, fill=(250, 250, 252), outline=PURPLE, width=4)
d.text((100, 552), "BANK NETWORK", font=ZONE, fill=PURPLE)

# operators
box(120, 630, 700, 780, "Operator workstations",
    [("Bank LAN only", BOXS)], accent=GREY)

# SRV-WEB
box(120, 900, 700, 1180, "SRV-WEB",
    [("Back-office server", BOXS),
     ("nginx + static files", BOXS),
     ("", BOXS),
     ("Not reachable from the internet", LBLB)], accent=PURPLE)

# SRV-APP
box(880, 900, 1560, 1180, "SRV-APP",
    [("Application server", BOXS),
     ("nginx front door + backend", BOXS),
     ("+ scheduled jobs", BOXS),
     ("The only internet-facing server", LBLB)], accent=PURPLE)

# SRV-DB
box(880, 1380, 1560, 1600, "SRV-DB",
    [("PostgreSQL 18, encrypted volume", BOXS),
     ("Accepts connections from SRV-APP only", BOXS)], accent=PURPLE)

# bank systems
box(1740, 900, 2290, 1180, "Bank systems",
    [("Core banking", BOXS),
     ("CheckAccount  ·  port 9494", LBL),
     ("Civil Registry", BOXS),
     ("GetCRSData  ·  port 5353", LBL)], accent=GREY)

arrow(405, 780, 405, 895, "HTTPS 443, internal name", 430, 805)
arrow(700, 1040, 875, 1040, "HTTP 8080", 715, 995)
arrow(1220, 470, 1220, 895, "HTTPS 443", 1245, 640)
arrow(1220, 1180, 1220, 1375, "TCP 5432", 1245, 1250)
arrow(1560, 1040, 1735, 1040, "outbound", 1580, 995)
arrow(1450, 900, 1450, 300, colour=GREY, dashed=True)
d.text((1475, 560), "outbound to the internet,", font=LBL, fill=GREY)
d.text((1475, 598), "through the firewall", font=LBL, fill=GREY)

# ---------------------------------------------------------------- footnote
d.text((60, 1700),
       "Every connection SRV-APP makes to an external service is outbound only. No external service "
       "initiates a connection into the bank.",
       font=NOTE, fill=GREY)
d.text((60, 1748),
       "Customer handsets contact Uqudo directly during document scan and liveness. That traffic "
       "does not cross the bank network.",
       font=NOTE, fill=GREY)

im.save("diagram.png", dpi=(300, 300))
print("written", im.size)
