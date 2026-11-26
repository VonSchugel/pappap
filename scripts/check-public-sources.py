"""Read public endpoints through production adapters and report actual coverage separately from fixture tests."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
from pathlib import Path
import sys
from zoneinfo import ZoneInfo

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"server"))
from engine import DEFAULTS
import sources as s
from tides import station_extrema

p=json.loads(DEFAULTS.read_text(encoding="utf8"))
day=datetime.now(ZoneInfo(p["zone"])).date().isoformat()


def model(marine):
    _,rows=s.forecast(day,p,marine=marine)
    key="swell_wave_height" if marine else "temperature_2m"
    valid=sum(isinstance(row.get(key),(int,float)) for row in rows)
    return bool(valid),f"{valid}/{len(rows)} dated readings for {key}"


def bulletin(marine):
    lines,covered=s.vigilance(day,marine,s.fetch)
    return covered,("Mohammedia area and bulletin date matched" if covered else lines[0])


def github():
    data,gaps=s.github({**p,"repos":["huggingface/lerobot"],"orgs":[]},day)
    return bool(data),f"{len(data)} parsed project or release records; {len(gaps)} gaps"


def hn():
    data,gaps=s.hackernews(p,day)
    linked=sum(any("news.ycombinator.com/item?id=" in link["url"] for link in item["links"]) for item in data)
    return bool(linked),f"{linked} discoveries with discussion links; {len(gaps)} gaps"


def tides():
    extrema=station_extrema(p)
    matching=[entry for entry in extrema if entry["at"].startswith(day)]
    return bool(matching),f"{len(matching)} station extrema on the requested date"


def devpost():
    data=s.fetch("https://devpost.com/api/hackathons",{"page":1})["hackathons"]
    # A successful HTTP response must contain at least one identifiable discovery listing.
    count=sum(bool(item.get("url") and item.get("title")) for item in data)
    return bool(count),f"{count} discovery listings available"


checks={"Open-Meteo weather":lambda:model(False),"Open-Meteo Marine":lambda:model(True),
        "DGM general":lambda:bulletin(False),"DGM marine":lambda:bulletin(True),"SHOM station":tides,
        "GitHub Releases":github,"Hacker News":hn,"Devpost discovery":devpost}


def perform(pair):
    name,check=pair
    try:
        verified,detail=check()
    except Exception as error:
        verified=False
        detail=type(error).__name__+(f" (HTTP {error.code})" if hasattr(error,"code") else "")
    return {"source":name,"verified":verified,"detail":detail}


if __name__=="__main__":
    with ThreadPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(perform,checks.items()))
    print("PUBLIC_SOURCE_RESULTS "+json.dumps({"date":day,"results":results},ensure_ascii=True))
