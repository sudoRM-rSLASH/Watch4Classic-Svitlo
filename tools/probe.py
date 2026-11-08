"""Діагностика: чи віддає сайт графік звичайним HTTP-запитом (без JS) і чи парситься він.
Запускається в GitHub Actions (job `probe`), результат дивись у логу."""
import re
import sys
import urllib.request

URL = ("https://energy-ua.info/grafik/%D0%9A%D1%80%D0%B5%D0%BC%D0%B5%D0%BD%D1%87%D1%83%D0%BA/"
       "%D0%A3%D0%BD%D1%96%D0%B2%D0%B5%D1%80%D1%81%D0%B8%D1%82%D0%B5%D1%82%D1%81%D1%8C%D0%BA%D0%B0/13-49%23fract%2310")

req = urllib.request.Request(URL, headers={
    "User-Agent": "LightWatch/1.0 (personal Wear OS tile)",
    "Accept-Language": "uk",
})
try:
    with urllib.request.urlopen(req, timeout=20) as r:
        html = r.read().decode("utf-8", "replace")
        print("HTTP", r.status, "bytes", len(html))
except Exception as e:  # noqa: BLE001
    print("FETCH FAILED:", e)
    sys.exit(0)

hours = re.findall(r'hour_info_from">(\d\d):\d\d</i>.*?<span class="hour_status ?([^"]*)', html, re.S)
print("hour blocks:", len(hours))
print("off hours  :", [h for h, c in hours if "hour_active" in c])
print("queue info :", re.findall(r'ch_num_info">(.*?)</p>', html, re.S))
print("day titles :", re.findall(r'ch_day_title">([^<]*)<', html))
print("title      :", re.findall(r"<title>(.*?)</title>", html, re.S))
if not hours:
    print("---- first 1500 chars ----")
    print(html[:1500])
