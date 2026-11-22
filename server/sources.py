"""Independent adapters. All network reads can be replaced by recorded responses in tests."""
import concurrent.futures as futures
import hashlib
import json
import os
import re
from datetime import date, datetime, timedelta, timezone
from html import unescape
from pathlib import Path
from urllib.parse import parse_qsl, quote, urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo
from tides import station_extrema

ROOT = Path(__file__).resolve().parents[1]
DATA = Path(os.getenv("CONTENT_DIR", ROOT / "server" / "data"))
WEATHER = "https://api.open-meteo.com/v1/forecast"
MARINE = "https://marine-api.open-meteo.com/v1/marine"
DGM = "https://marocmeteo.ma/fr"
BATHING = "https://labo.environnement.gov.ma/"
FIELDS = "temperature_2m,apparent_temperature,precipitation_probability,precipitation,wind_speed_10m,wind_direction_10m,wind_gusts_10m,relative_humidity_2m,uv_index"
SEA = "swell_wave_height,swell_wave_period,swell_wave_direction,sea_surface_temperature"
HN = "https://hacker-news.firebaseio.com/v0/"
GH = "https://api.github.com"

def fetch(url, params=None, headers=None):
    if params:
        url += ("&" if "?" in url else "?") + urlencode(params)
    req = Request(url, headers={"User-Agent": "Marqicci-Daybook/1.0", **(headers or {})})
    with urlopen(req, timeout=12) as response:
        return json.loads(response.read(5000000))

def iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")

def link(title, url):
    return {"title": title, "url": url}

def block(title, *lines):
    return {"title": title, "lines": list(lines)}

def catalog(name):
    path = DATA / (name + ".json")
    return json.loads(path.read_text(encoding="utf8")) if path.exists() else []

def notices(day, prefs):
    # Reviewed, location-scoped evidence. Each official record has dates and attribution.
    out = []
    for r in catalog("coast"):
        if not r.get("source", "").startswith("https://") or not r.get("issued") or not r.get("text"):
            continue
        if r["kind"] == "sample":
            valid = r["issued"][:10] <= day
        else:
            valid = r.get("from", "") <= day <= r.get("through", "")
        local = abs(r["lat"]-prefs["lat"]) <= r["radiusDegrees"] and abs(r["lon"]-prefs["lon"]) <= r["radiusDegrees"]
        if valid and (local or r["kind"] == "weather" and r.get("area") == "Mohammedia"):
            out.append(r)
    return sorted(out, key=lambda x: x["issued"], reverse=True)

def forecast(day, p, marine=False, beach=False, read=fetch):
    pin = (p["lat"], p["lon"]) if marine or beach else (33.69, -7.38)
    args = dict(latitude=pin[0], longitude=pin[1], timezone=p["zone"], start_date=day, end_date=day, hourly=SEA if marine else FIELDS)
    if not marine:
        args["daily"] = "sunrise,sunset"
    raw = read(MARINE if marine else WEATHER, args)
    h = raw["hourly"]
    rows = [{k: v[i] for k,v in h.items()} for i,t in enumerate(h["time"]) if t.startswith(day)]
    if not rows or not any(isinstance(n,(int,float)) for row in rows for n in row.values()):
        raise ValueError("No forecast values for selected date")
    return raw, rows

def val(row, key, unit=""):
    n = row.get(key)
    return "unknown" if n is None else f"{n:g}{unit}"

def direction(n):
    return "unknown" if n is None else f"{['N','NE','E','SE','S','SW','W','NW'][int((n+22.5)//45)%8]} {n:g}°"

def vigilance(day, marine, read):
    zone = "marine" if marine else "generale"
    try:
        raw = read(DGM+"/mm-vigilance/data/"+zone+"/0")
        if not raw.get("ok") or raw["date"] != day or raw.get("zone") != zone:
            raise ValueError("Bulletin validity")
        areas = [a for a in raw["areas"] if re.search(r"mohamm[ae]dia",a["name"],re.I)]
        levels = ["green","yellow","orange","red"]
        hazards = {"wf":"strong wind","fp":"heavy rain","vc":"heat","vg":"dangerous waves","hd":"dangerous swell","or":"thunderstorms"}
        lines = [a["name"]+" · "+levels[a["level"]]+" · "+", ".join(hazards.get(k,k) for k in a.get("phenos",{})) for a in areas]
        return (lines or ["No Mohammedia area listed in this DGM bulletin."])+["Issued "+raw["issued"]+" · valid "+day], True
    except Exception:
        return ["Current DGM bulletin unavailable or outside its validity date."], False

def weather(day, p, read=fetch):
    raw, rows = forecast(day,p,read=read)
    changes, before = [], None
    for r in rows:
        flags = tuple((r.get(k) or 0)>=n for k,n in [("precipitation_probability",50),("wind_gusts_10m",35),("uv_index",3)])
        if before is not None and flags != before:
            labels = [("Rain becomes more likely","Rain chance eases"),("Gusts strengthen","Gusts ease"),("UV rises above the planning threshold","UV eases")]
            changes.append(r["time"][11:]+" · "+", ".join(labels[i][0 if flags[i] else 1] for i in range(3) if flags[i]!=before[i]))
        before = flags
    at = lambda hour: min(rows, key=lambda r:abs(int(r["time"][11:13])-hour))
    summary = "Morning around %s, afternoon %s and evening %s." % tuple(val(at(h),"temperature_2m","°C") for h in (8,15,20))
    if changes:
        summary += " "+changes[0]+"."
    warnings = [r for r in notices(day,p) if r["kind"]=="weather"]
    hourly = []
    for r in rows:
        hourly.append(f"{r['time'][11:]}   {val(r,'temperature_2m','°C')} · feels {val(r,'apparent_temperature','°C')}\nRain {val(r,'precipitation_probability','%')} · {val(r,'precipitation',' mm')} | Wind {direction(r.get('wind_direction_10m'))} {val(r,'wind_speed_10m',' km/h')} · gust {val(r,'wind_gusts_10m',' km/h')}\nHumidity {val(r,'relative_humidity_2m','%')} · UV {val(r,'uv_index')}")
    official,ok = vigilance(day,False,read)
    return dict(summary=summary,gaps=[] if ok else ["DGM warning refresh unavailable"],blocks=[block("Changes for outdoor plans",*(changes or ["No threshold crossings in the available hours."])),block("Daylight",f"Sunrise {raw['daily']['sunrise'][0]} · Sunset {raw['daily']['sunset'][0]}"),block("Official warnings",*official,*[r["text"]+" · "+r["issued"] for r in warnings]),block("Hourly forecast",*hourly),block("Forecast record",f"Forecast valid {rows[0]['time']} to {rows[-1]['time']} · {p['zone']}","Model issue time unavailable from the blended endpoint.")],links=[link("Open-Meteo forecast","https://open-meteo.com/en/docs"),link("DGM vigilance",DGM+"/vigilance/generale")]+[link("Official warning",r["source"]) for r in warnings])

def swim(day, p, read=fetch):
    evidence = notices(day,p)
    closed = next((r for r in evidence if r["kind"]=="closure"),None)
    gaps, sections = [], []
    times = (p["swimStart"],p["swimEnd"])
    def window(rows):
        return [r for r in rows if r["time"][11:16] < times[1] and r["time"][11:13]+":59" >= times[0]]
    try:
        raw, rows = forecast(day,p,marine=True,read=read)
        sea = window(rows)
        if not sea: raise ValueError("empty window")
        sections.append(block("Regional offshore model",f"Model grid {raw['latitude']:.4f}, {raw['longitude']:.4f} · estimated sea-surface values",*[f"{r['time'][11:]} · Swell {val(r,'swell_wave_height',' m')} / {val(r,'swell_wave_period',' s')} from {direction(r.get('swell_wave_direction'))} · Water {val(r,'sea_surface_temperature','°C')}" for r in sea]))
    except Exception:
        sea = []
        gaps.append("Offshore swell and temperature unavailable")
    try:
        _, wind = forecast(day,p,beach=True,read=read)
        sections.append(block("Coastal wind",*[f"{r['time'][11:]} · {direction(r.get('wind_direction_10m'))} {val(r,'wind_speed_10m',' km/h')} · gust {val(r,'wind_gusts_10m',' km/h')}" for r in window(wind)]))
    except Exception:
        gaps.append("Coastal wind unavailable")
    tides = sorted([t for r in evidence if r["kind"]=="tides" for t in r.get("extrema",[])],key=lambda t:t["at"])
    tide_source = "Reviewed station table"
    if not tides:
        try:
            tides = station_extrema(p)
            tide_source = "SHOM Mohammedia harbor prediction · local calendar time"
        except Exception:
            gaps.append("SHOM tide table unavailable")
    def phase(clock):
        moment=day+"T"+clock
        prev=[x for x in tides if x["at"]<=moment]
        after=[x for x in tides if x["at"]>moment]
        if not prev or not after or prev[-1]["type"]==after[0]["type"]: return "unverified"
        return "rising" if prev[-1]["type"]=="low" else "falling"
    tide_lines=[t["at"]+" · "+t["type"] for t in tides if t["at"][:10]==day]
    sections.append(block("Local tides",*([tide_source]+tide_lines if tide_lines else ["Reliable local high and low times unavailable."]),f"Phase at {times[0]}: {phase(times[0])} · at {times[1]}: {phase(times[1])}"))
    sample=next((r for r in evidence if r["kind"]=="sample"),None)
    sections.extend([block("Marine notices",*([r["text"]+" · "+r["issued"] for r in evidence if r["kind"] in ("marine","closure")] or ["Current official closure and advisory status unverified."])),block("Most recent dated sample",sample["issued"]+" · "+sample["text"] if sample else "No verified dated water-quality sample available."),block("At the rocks","Watch for waves crossing the barrier. Check flow through gaps, posted flags and a usable exit as the tide changes. Follow lifeguards and closures.")])
    assessment = "Closed · "+closed["text"] if closed else "Local conditions unverified. "
    if sea and not closed: assessment += f"Offshore swell starts at {val(sea[0],'swell_wave_height',' m')} with a {val(sea[0],'swell_wave_period',' s')} period. "
    if not closed: assessment+="Check the rock barrier and exit on site before deciding."
    official,ok = vigilance(day,True,read)
    if not ok: gaps.append("DGM marine bulletin unavailable")
    sections.insert(1,block("DGM regional marine bulletin",*official))
    return dict(summary=assessment,closed=bool(closed),blocks=[block("Swim window"," – ".join(times)+f" · pin {p['lat']:.5f}, {p['lon']:.5f}")]+sections,gaps=gaps,links=[link("Open-Meteo Marine","https://open-meteo.com/en/docs/marine-weather-api"),link("DGM marine vigilance",DGM+"/vigilance/marine"),link("SHOM · Mohammedia harbor","https://maree.shom.fr/harbor/MOHAMMEDIA"),link("National bathing-water laboratory",BATHING)]+[link(r["kind"].title()+" · "+r["issued"],r["source"]) for r in evidence])

def canonical(url):
    u=urlsplit(url)
    return urlunsplit((u.scheme.lower(),u.netloc.lower().removeprefix("www."),u.path.rstrip("/"),urlencode(sorted((k,v) for k,v in parse_qsl(u.query) if not k.startswith("utm_") and k not in ("ref","s","t"))),""))

def topic(text):
    t=text.lower()
    if re.search(r"robot|lerobot|ros2|humanoid|actuator|manipulation",t): return "Robotics"
    if re.search(r"llama\.cpp|inference|training tool|vllm|compiler|sdk|transformers|coding agent|quantiz|runtime",t): return "Developer tools"
    if re.search(r"model|weights|llm|qwen|deepseek|multimodal|language model|diffusion|gpt",t): return "AI models"
    if re.search(r"\bai\b|developer tool|machine learning|inference|agentic",t): return "Developer tools"
    return ""

def plain(s):
    return " ".join(unescape(re.sub(r"<[^>]+>"," ",s or "")).split())

def verified_model_access(c, body, read):
    match=re.search(r"(?:model card|model weights|weights|model checkpoint)[^\n]{0,90}?(https://huggingface\.co/([\w.-]+/[\w.-]+))",body,re.I)
    if not match: return
    model=match[2].rstrip(".")
    try:
        info=read("https://huggingface.co/api/models/"+model)
        filenames=[f.get("rfilename","").lower() for f in info.get("siblings",[])]
        has_weights=any(f.endswith((".safetensors",".gguf",".bin")) for f in filenames)
        license_name=(info.get("cardData") or {}).get("license") or "unverified"
        access="gated" if info.get("gated") else "public repository"
        c["access"]+=f" · linked weights {'listed' if has_weights else 'unverified'} ({access}) · license {license_name}"
        c["links"].append(link("Model card and files","https://huggingface.co/"+model))
    except Exception: pass

def implications(topic_name,notes):
    rules=[
        (r"breaking|deprecat|migration|remov.{0,24}api","Existing callers may need changes before upgrading.","Compare the migration notes to the interfaces your project uses."),
        (r"quantiz|fp8|int4|kv.cache","The change affects the memory or precision trade-off during inference.","Measure memory use and output quality with your usual model and prompts."),
        (r"latency|throughput|batching|accelerat","The reported performance change could affect deployment capacity.","Reproduce the reported benchmark using the same hardware, batch size and precision."),
        (r"dataset|benchmark|evaluation","The data and evaluation protocol determine which results you can compare.","Inspect the dataset split and reproduce the stated evaluation settings."),
        (r"robot|manipulat|actuator","The setup and policy inputs determine whether the demo transfers to another robot.","Check the hardware list and calibration or simulation instructions in the primary source."),
        (r"weight|checkpoint","A downloadable checkpoint lets you test the model under your own constraints.","Check the model card, license and memory requirements before loading weights."),
    ]
    for pattern,why,action in rules:
        if re.search(pattern,notes,re.I): return why,action
    return {"Robotics":"Hardware and evaluation conditions determine how reproducible the demonstration is.","AI models":"The model's access terms and evaluations determine whether it fits a local experiment.","Developer tools":"Compatibility with your toolchain determines whether you can adopt this change."}[topic_name],"Open the source and follow its setup or reproduction instructions."

def candidate(title,url,when,source,change="",status="Unverified claim",score=1,context="",known_date=True):
    subject=topic(title+" "+context+" "+change)
    if not subject or not url.startswith(("https://","http://")): return None
    changed=plain(change)[:420] or "The source provides a headline. Technical changes still need primary confirmation."
    why,action=implications(subject,title+" "+changed)
    return dict(title=plain(title),topic=subject,status=status,date=when[:10] if known_date else "",seen=when,what=plain(title),change=changed,why=why,**{"try":action},url=url,access="Access terms unverified",links=[link(source,url)],score=score,aliases=[canonical(url)])

def github(p,day,read=fetch):
    headers={"Accept":"application/vnd.github+json"}
    if os.getenv("GITHUB_TOKEN"): headers["Authorization"]="Bearer "+os.environ["GITHUB_TOKEN"]
    stories,gaps=[],[]
    def get(path, n): return read(GH+path,{"per_page":n},headers)
    repos=set(p["repos"])
    for org in p["orgs"]:
        try:
            new=read(GH+"/orgs/"+org+"/repos",{"sort":"created","direction":"desc","per_page":6},headers)
            for repo in new:
                c=candidate(repo["full_name"],repo["html_url"],repo["created_at"],"GitHub project",repo.get("description"),"Published code",3)
                if c: stories.append(c)
        except Exception: gaps.append("GitHub organization · "+org+" unavailable")
    for repo in sorted(repos):
        try:
            releases=get("/repos/"+repo+"/releases",4)
            for r in releases:
                if r["draft"] or not r.get("published_at"): continue
                body=r.get("body") or ""
                notes=[plain(re.sub(r"^[ #*\-]+","",s)) for s in body.splitlines() if len(plain(s))>35 and not s.lstrip().startswith(("<img","![","http","[!","<picture","<source"))]
                c=candidate(repo+" · "+(r["name"] or r["tag_name"]),r["html_url"],r["published_at"],"GitHub release",next(iter(notes),""),"Preview" if r["prerelease"] else "Shipped",8,repo+" "+body[:250])
                if c:
                    c["access"]="Tagged source available"+(" · downloadable assets" if r.get("assets") else "")
                    if c["topic"]=="Robotics": c["access"]="Published code · hardware availability unverified"
                    if c["topic"]=="AI models": c["access"]+=" · weights, model license and API unverified"
                    c["score"]+=int(not r["prerelease"])*2+int(bool(r.get("assets")))
                    verified_model_access(c,body,read)
                    for url in re.findall(r"(?:blog post|announcement|release blog)[:\s]+(https?://[^\s)]+)",body,re.I):
                        c["aliases"].append(canonical(url)); c["links"].append(link("Announcement",url))
                    stories.append(c)
            if not releases:
                commits=read(GH+"/repos/"+repo+"/commits",{"path":"CHANGELOG.md","per_page":2},headers)
                for cmt in commits:
                    c=candidate(repo+" · changelog update",cmt["html_url"],cmt["commit"]["committer"]["date"],"Changelog",cmt["commit"]["message"],"Published code",4,repo)
                    if c: stories.append(c)
        except Exception: gaps.append("GitHub · "+repo+" unavailable")
    return stories,gaps

def hackernews(p,day,read=fetch):
    ids=[]; gaps=[]
    for feed in ("topstories","showstories"):
        try: ids.extend(read(HN+feed+".json")[:35])
        except Exception: gaps.append("HN · "+feed+" unavailable")
    def one(i):
        try: r=read(HN+f"item/{i}.json")
        except Exception: return None,True
        if not r or r.get("dead") or r.get("deleted") or r.get("type")!="story": return None,False
        discussion=f"https://news.ycombinator.com/item?id={i}"
        c=candidate(r.get("title",""),r.get("url",discussion),datetime.fromtimestamp(r["time"],timezone.utc).isoformat(),"Announcement",r.get("text",""),score=2+min(3,r.get("score",0)//150),known_date=False)
        if c: c["links"].append(link("HN discussion",discussion))
        return c,False
    with futures.ThreadPoolExecutor(max_workers=10) as pool: rows=list(pool.map(one,dict.fromkeys(ids)))
    if any(bad for _,bad in rows): gaps.append("Some HN items unavailable")
    return [c for c,_ in rows if c],gaps

def twitter(p,day,read=fetch):
    bearer=os.getenv("X_BEARER_TOKEN")
    if not bearer or not p["accounts"]: return [],["X · access or followed accounts missing"]
    stories,gaps=[],[]; h={"Authorization":"Bearer "+bearer}
    for acct in p["accounts"]:
        try:
            uid=read("https://api.x.com/2/users/by/username/"+quote(acct),headers=h)["data"]["id"]
            posts=read("https://api.x.com/2/users/"+uid+"/tweets",{"max_results":10,"tweet.fields":"created_at,entities","exclude":"retweets,replies"},h)
            for r in posts.get("data",[]):
                post="https://x.com/"+acct+"/status/"+r["id"]
                urls=r.get("entities",{}).get("urls",[])
                url=next((u["expanded_url"] for u in urls if u.get("expanded_url") and not u["expanded_url"].startswith("https://x.com/")),post)
                c=candidate(r["text"][:130],url,r["created_at"],"Linked source",r["text"],known_date=False)
                if c: c["links"].append(link("@"+acct,post)); stories.append(c)
        except Exception: gaps.append("X · @"+acct+" unavailable")
    return stories,gaps

NEWS={"github":github,"hn":hackernews,"x":twitter}

def edition(day,p,adapters=None):
    stories=[]; gaps=[]
    adapters=NEWS if adapters is None else adapters
    with futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs={pool.submit(fn,p,day):k for k,fn in adapters.items() if p["sources"].get(k)}
        for job,key in jobs.items():
            try:
                found,missing=job.result(); stories.extend(found); gaps.extend(missing)
            except Exception: gaps.append(key+" · source unavailable")
    start=date.fromisoformat(day)-timedelta(days=6)
    keep=[]
    for s in sorted(stories,key=lambda s:(s["score"],s["seen"],s["url"]),reverse=True):
        seen=datetime.fromisoformat(s["seen"].replace("Z","+00:00")).astimezone(ZoneInfo(p["zone"])).date()
        if not start<=seen<=date.fromisoformat(day) or s["topic"] not in p["topics"]: continue
        matches=[k for k in keep if set(k["aliases"]).intersection(s["aliases"])]
        if matches:
            head=matches[0]
            for other in [s]+matches[1:]:
                head["aliases"]=list(dict.fromkeys(head["aliases"]+other["aliases"]))
                for l in other["links"]:
                    if l not in head["links"]: head["links"].append(l)
            for other in matches[1:]: keep.remove(other)
        else: keep.append(s.copy())
    keep=keep[:p["length"]]
    if keep: keep=[keep[0]]+sorted(keep[1:],key=lambda a:p["topics"].index(a["topic"]))
    return dict(summary=keep[0]["title"] if keep else "No matching releases or discoveries in the past seven days.",stories=keep,gaps=gaps,blocks=[])

def hacks(day,p,read=fetch):
    records=catalog("events"); events=[]; gaps=[]
    try: discovered=read("https://devpost.com/api/hackathons",{"page":1})["hackathons"]
    except Exception: discovered=[]; gaps.append("Devpost discovery unavailable")
    vetted={canonical(r["url"]):r for r in records}
    for r in discovered:
        url=canonical(r["url"]); loc=r.get("displayed_location",{}).get("location","")
        if url in vetted or r.get("open_state")!="open" or r.get("invite_only"): continue
        if re.search(r"Morocco|Maroc|Casablanca|Rabat|Mohammedia|Marrakech|Tangier",loc,re.I):
            vetted[url]=dict(title=r["title"],url=r["url"],mode="In person · "+loc,eligibility="Listed in Morocco · check organizer rules",country="MA",listed=True)
    for r in vetted.values():
        if r.get("through","9999-12-31")<day or r.get("from","")>day: continue
        if r.get("country")!="MA" and not (r.get("remote") and r.get("moroccoEligible") and r.get("rules")): continue
        events.append(r)
    return dict(summary=f"{len(events)} qualifying opportunities. Application deadlines appear when verified.",events=events,blocks=[block("Coverage","Online listings require reviewed eligibility evidence. A submission deadline is kept distinct from an application deadline.")],gaps=gaps,links=[link("Devpost discovery","https://devpost.com/hackathons")])

REPORTS={"weather":weather,"swim":swim,"tech":edition,"hacks":hacks}

