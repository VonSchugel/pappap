"""Extract only the published Mohammedia harbor tide table and its explicit UTC offset."""
import re
from datetime import datetime, timedelta, timezone
from html import unescape
from zoneinfo import ZoneInfo

SHOM="https://maree.shom.fr/harbor/MOHAMMEDIA"

def clean(html):
    return " ".join(unescape(re.sub(r"<[^>]*>"," ",html)).split())

def parse_shom(html, zone):
    content=clean(html)
    if not re.search(r"Mohammedia\s*\(Maroc\)",content,re.I): raise ValueError("Wrong station")
    offset=re.search(r"(?:Hour|Heure)\s+UTC\s*([+-])\s*(\d{1,2})(?::(\d{2}))?\b",content,re.I)
    if not offset: raise ValueError("Station time offset unavailable")
    delta=timedelta(hours=int(offset[2]),minutes=int(offset[3] or 0))
    source=timezone(delta if offset[1]=="+" else -delta)
    extrema={}
    for table in re.findall(r"<table\b[^>]*>.*?</table>",html,re.I|re.S):
        label=re.search(r'<th[^>]*class="[^"]*tide-title[^"]*"[^>]*>(.*?)</th>',table,re.S)
        if not label: continue
        dt=datetime.strptime(clean(label[1]),"%A %B %d, %Y")
        for row in re.findall(r"<tr\b[^>]*>(.*?)</tr>",table,re.I|re.S):
            cols=[clean(c) for c in re.findall(r"<td\b[^>]*>(.*?)</td>",row,re.I|re.S)]
            if len(cols)<3 or cols[0] not in ("LW","HW"): continue
            local=datetime.combine(dt.date(),datetime.strptime(cols[1],"%H:%M").time(),source).astimezone(ZoneInfo(zone))
            stamp=local.strftime("%Y-%m-%dT%H:%M")
            extrema[stamp]={"at":stamp,"type":"high" if cols[0]=="HW" else "low","metres":float(cols[2])}
    if not extrema: raise ValueError("No tide extrema")
    return sorted(extrema.values(),key=lambda x:x["at"])

def read_station_table(page, day, zone):
    # SHOM renders the heading, clock label and rows independently. A visible
    # heading alone can belong to an incomplete table.
    page.wait_for_function(r"""day => {
        const text = node => (node.textContent || '').trim();
        const station = [...document.querySelectorAll('h3')].some(n => /Mohammedia\s*\(Maroc\)/i.test(text(n)));
        const utc = [...document.querySelectorAll('.chart-label')].some(n => /(?:Hour|Heure)\s+UTC\s*[+-]0(?:0|:00)?\s*$/i.test(text(n)));
        const ready = [...document.querySelectorAll('table')].some(table => {
            const heading = table.querySelector('th.tide-title');
            if (!heading || !text(heading).replace(/\b0(\d)\b/g,'$1').includes(day)) return false;
            const rows = [...table.querySelectorAll('tr')].map(row =>
                [...row.querySelectorAll('td')].map(text));
            return ['HW','LW'].every(type => rows.some(c =>
                c[0] === type && /^\d{2}:\d{2}$/.test(c[1]) && /^\d+(?:\.\d+)?$/.test(c[2])));
        });
        return station && utc && ready;
    }""",arg=day.strftime("%A %B ")+str(day.day)+day.strftime(", %Y"),timeout=18000)
    extrema=parse_shom(page.content(),zone)
    if not any(row["at"].startswith(day.isoformat()) for row in extrema):
        raise ValueError("No extrema for the requested local date")
    return extrema

def station_extrema(p, day=None):
    if abs(p["lat"]-33.71078)>0.04 or abs(p["lon"]+7.36798)>0.04: return []
    from playwright.sync_api import sync_playwright
    with sync_playwright() as engine:
        browser=engine.chromium.launch()
        try:
            page=browser.new_page(locale="en-GB",timezone_id="UTC")
            requested=datetime.fromisoformat(day).date() if day else datetime.now(ZoneInfo(p["zone"])).date()
            start=requested-timedelta(days=1)
            page.goto(f"{SHOM}/hlt?date={start.isoformat()}&utc=0",wait_until="domcontentloaded",timeout=18000)
            return read_station_table(page,requested,p["zone"])
        finally: browser.close()

