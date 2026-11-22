import copy
import json
import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
from zoneinfo import ZoneInfo
from pathlib import Path
from unittest.mock import patch
from engine import Store,DEFAULTS
import sources as s
from tides import parse_shom

def prefs(): return json.loads(DEFAULTS.read_text(encoding="utf8"))
D="2026-11-22"
def fixture_story(title,url,src,score):
    return s.candidate(title,url,D+"T05:30:00Z",src,"AI inference runtime changes","Shipped",score)
class Edition(unittest.TestCase):
    def test_merge_primary_with_hn_and_x_outage(self):
        url="https://github.com/acme/model/releases/tag/v2"
        release=fixture_story("AI model v2",url,"GitHub",8)
        hn=fixture_story("Show HN: Model demo",url+"?utm_source=hn","Announcement",2)
        hn["links"].append(s.link("HN discussion","https://news.ycombinator.com/item?id=12"))
        result=s.edition(D,prefs(),{"github":lambda *_:([release],[]),"hn":lambda *_:([hn],[]),"x":lambda *_:(_ for _ in ()).throw(TimeoutError())})
        self.assertEqual(len(result["stories"]),1)
        self.assertEqual(result["stories"][0]["title"],release["title"])
        self.assertIn("HN discussion",[x["title"] for x in result["stories"][0]["links"]])
        self.assertTrue(any("x" in x.lower() for x in result["gaps"]))
    def test_different_versions_stay_distinct(self):
        rows=[fixture_story("AI model "+v,"https://example.org/releases/"+v,"GitHub",8) for v in ["v1","v2"]]
        self.assertEqual(len(s.edition(D,prefs(),{"github":lambda *_:(rows,[])})["stories"]),2)
    def test_topics_and_length(self):
        p=prefs();p["topics"]=["Robotics"]
        self.assertFalse(s.edition(D,p,{"hn":lambda *_:([fixture_story("Model","https://example.org/a","HN",2)],[])})["stories"])
    def test_x_missing(self):
        with patch.dict(os.environ,{"X_BEARER_TOKEN":""}):
            items,gaps=s.twitter(prefs(),D)
        self.assertFalse(items);self.assertTrue(gaps)

class Engine(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store=Store(Path(self.tmp.name)/"store.sqlite")
    def test_restart_and_retry(self):
        calls=[]
        def gen(day,p): calls.append(day);return {"stories":[],"summary":"Published"}
        for _ in range(3):self.store.generate(D,"tech",prefs(),registry={"tech":gen})
        self.assertEqual(calls,[D]);self.assertEqual(len(Store(self.store.path).day(D)),1)
    def test_concurrent_publish(self):
        start=threading.Event();release=threading.Event()
        def gen(*args):start.set();release.wait(5);return {"summary":"one"}
        with ThreadPoolExecutor() as pool:
            first=pool.submit(self.store.generate,D,"tech",prefs(),False,{"tech":gen})
            self.assertTrue(start.wait(3))
            self.assertIsNone(self.store.generate(D,"tech",prefs(),registry={"tech":gen}))
            release.set();first.result()
        self.assertEqual(len(self.store.day(D)),1)
    def test_pause_and_time_edit(self):
        dt=datetime(2026,11,22,9,0,tzinfo=ZoneInfo("Africa/Casablanca"))
        p=prefs();p["schedule"]["tech"]["time"]="10:00";self.store.save(p)
        registry={"tech":lambda *_:{"summary":"published"}}
        self.assertEqual(self.store.tick(dt,registry),{})
        p["schedule"]["tech"]["time"]="09:00";p["schedule"]["tech"]["enabled"]=False;self.store.save(p)
        self.assertEqual(self.store.tick(dt,registry),{})
        p["schedule"]["tech"]["enabled"]=True;self.store.save(p)
        self.assertIn("tech",self.store.tick(dt,registry))
    def test_local_midnight(self):
        p=prefs();p["zone"]="Europe/Paris";p["schedule"]["tech"]["time"]="00:00";self.store.save(p)
        self.store.tick(datetime(2026,11,21,23,1,tzinfo=timezone.utc),{"tech":lambda *_:{"summary":"new day"}})
        self.assertEqual(len(self.store.day(D)),1)
    def test_failed_refresh_keeps_original(self):
        self.store.generate(D,"tech",prefs(),registry={"tech":lambda *_:{"summary":"cached"}})
        with self.assertRaises(TimeoutError):
            self.store.generate(D,"tech",prefs(),True,{"tech":lambda *_:(_ for _ in ()).throw(TimeoutError())})
        self.assertEqual(self.store.day(D)[0]["summary"],"cached")

class Coast(unittest.TestCase):
    def test_closure_overrides_model_failure(self):
        record={"kind":"closure","from":D,"through":D,"issued":D,"text":"Beach closed","source":"https://example.org/notice","lat":33.71078,"lon":-7.36798,"radiusDegrees":.01}
        with patch.object(s,"catalog",return_value=[record]),patch.object(s,"station_extrema",return_value=[]):
            r=s.swim(D,prefs(),lambda *a,**k:(_ for _ in ()).throw(TimeoutError()))
        self.assertTrue(r["closed"]);self.assertTrue(r["summary"].startswith("Closed"))
        self.assertTrue(r["gaps"])
    def test_sample_scoping_and_age(self):
        base={"kind":"sample","lat":33.71078,"lon":-7.36798,"radiusDegrees":.01,"text":"Sample from a named station","source":"https://example.org/sample"}
        with patch.object(s,"catalog",return_value=[dict(base,issued="2026-10-10"),dict(base,issued="2026-12-01"),dict(base,issued=D,lon=-4)]):
            self.assertEqual([r["issued"] for r in s.notices(D,prefs())],["2026-10-10"])
    def test_station_offset(self):
        html='Mohammedia (Maroc) Hour UTC+0<table><th class="tide-title">Sunday November 22, 2026</th><tr><td>LW</td><td>05:50</td><td>1.19</td></tr></table>'
        self.assertEqual(parse_shom(html,"Europe/Paris")[0]["at"],D+"T06:50")
        with self.assertRaises(ValueError): parse_shom(html.replace("UTC+0",""),"Africa/Casablanca")
    def test_stale_warning(self):
        lines,ok=s.vigilance(D,False,lambda *_:{"ok":True,"date":"2020-01-01"})
        self.assertFalse(ok)
    def test_remote_eligibility(self):
        events=[{"url":"https://example.org/us","remote":True,"country":"US"},{"url":"https://example.org/ma","country":"MA","title":"Local"},{"url":"https://example.org/world","remote":True,"moroccoEligible":True,"rules":"https://example.org/rules","title":"Remote"}]
        with patch.object(s,"catalog",return_value=events):
            r=s.hacks(D,prefs(),lambda *args:{"hackathons":[]})
        self.assertEqual({e["title"] for e in r["events"]},{"Local","Remote"})
    def test_online_discovery_requires_affirmative_eligibility(self):
        self.assertTrue(s.eligibility_evidence("This hackathon is open to adult participants worldwide."))
        self.assertFalse(s.eligibility_evidence("Promote your work worldwide. Open to residents of Ontario."))
        self.assertFalse(s.eligibility_evidence("Open to participants worldwide. Excluding residents of Morocco."))
        self.assertFalse(s.eligibility_evidence("Open to participants worldwide. Finalists must reside in Canada."))

