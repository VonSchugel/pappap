"""Failure isolation, date boundaries and cached publication regressions."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event
import unittest
from unittest.mock import patch

from engine import Store
from test_core import D, prefs, fixture_story
import sources as s


class SourceRecovery(unittest.TestCase):
    def test_missing_hourly_reading_does_not_announce_improvement(self):
        def read(url, args=None):
            if url != s.WEATHER:
                return {}
            return {"daily": {"sunrise": [D+"T08:00"], "sunset": [D+"T18:00"]},
                    "hourly": {"time": [D+"T08:00",D+"T09:00",D+"T10:00"],
                               "temperature_2m": [20,21,22], "wind_gusts_10m": [50,None,45]}}
        with patch.object(s,"catalog",return_value=[]):
            report=s.weather(D,prefs(),read)
        changes=report["blocks"][0]["lines"]
        self.assertFalse(any("gusts ease" in line.lower() or "gusts strengthen" in line.lower() for line in changes))
        self.assertTrue(any("unknown" in line for block in report["blocks"] if block["title"]=="Hourly forecast" for line in block["lines"]))

    def test_official_bulletin_for_a_different_area_is_a_coverage_gap(self):
        for marine,zone in [(True,"marine"),(False,"generale")]:
            raw={"ok":True,"date":D,"zone":zone,"issued":D,"areas":[{"name":"Agadir","level":0}]}
            lines,covered=s.vigilance(D,marine,lambda *_:raw)
            self.assertFalse(covered)
            self.assertTrue(lines)

    def test_missing_daylight_does_not_discard_hourly_forecast(self):
        def read(url,args=None):
            if url != s.WEATHER: return {}
            return {"hourly":{"time":[D+"T08:00"],"temperature_2m":[18]}}
        with patch.object(s,"catalog",return_value=[]):
            result=s.weather(D,prefs(),read)
        self.assertIn("18",result["summary"])
        self.assertIn("daylight"," ".join(result["gaps"]).lower())

    def test_one_incomplete_hn_record_does_not_drop_its_neighbour(self):
        def read(url):
            if url.endswith("stories.json"): return [1,2]
            if url.endswith("/1.json"): return {"type":"story","title":"Robot with missing timestamp"}
            return {"type":"story","time":int(datetime(2026,11,22,tzinfo=timezone.utc).timestamp()),"title":"Show HN: Robot demo","url":"https://example.org/robot"}
        stories,gaps=s.hackernews(prefs(),D,read)
        self.assertEqual(len(stories),1)
        self.assertTrue(gaps)
        self.assertTrue(any("item?id=2" in item["url"] for item in stories[0]["links"]))

    def test_invalid_publication_date_does_not_abort_an_edition(self):
        good=fixture_story("AI model v2","https://example.org/model","GitHub",8)
        bad={**good,"seen":"unknown","url":"https://example.org/bad"}
        report=s.edition(D,prefs(),{"github":lambda *_:([bad,good],[])})
        self.assertEqual([story["url"] for story in report["stories"]],[good["url"]])
        self.assertTrue(report["gaps"])

    def test_date_window_uses_local_midnight_and_includes_seventh_day(self):
        p=prefs();p["zone"]="Asia/Tokyo"
        base=fixture_story("AI model","https://example.org/model","GitHub",8)
        dates=["2026-11-15T14:59:59Z","2026-11-15T15:00:00Z","2026-11-22T14:59:59Z","2026-11-22T15:00:00Z"]
        rows=[{**base,"seen":stamp,"url":base["url"]+str(i),"aliases":[str(i)]} for i,stamp in enumerate(dates)]
        report=s.edition(D,p,{"github":lambda *_:(rows,[])})
        self.assertEqual({r["seen"] for r in report["stories"]},set(dates[1:3]))

    def test_discovery_with_null_location_keeps_other_events(self):
        hits=[{"url":"https://example.org/no-location","open_state":"open","displayed_location":None},
              {"title":"Robotics in Rabat","url":"https://example.org/ma","open_state":"open","displayed_location":{"location":"Rabat, Morocco"}}]
        with patch.object(s,"catalog",return_value=[]):
            report=s.hacks(D,prefs(),lambda *_:{"hackathons":hits})
        self.assertEqual([r["title"] for r in report["events"]],["Robotics in Rabat"])


class PublicationRecovery(unittest.TestCase):
    def setUp(self):
        temp=TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.store=Store(Path(temp.name)/"qa.sqlite")

    def test_expired_worker_returns_committed_winner(self):
        entered=Event(); resume=Event();p=prefs()
        def slow(*_):
            entered.set()
            if not resume.wait(10): raise TimeoutError()
            return {"summary":"stale"}
        with ThreadPoolExecutor() as pool:
            first=pool.submit(self.store.generate,D,"tech",p,False,{"tech":slow})
            try:
                self.assertTrue(entered.wait(3))
                with closing(self.store.connect()) as conn:
                    conn.execute("UPDATE jobs SET until=0 WHERE day=? AND kind='tech'",(D,))
                winner=self.store.generate(D,"tech",p,registry={"tech":lambda *_:{"summary":"winner"}})
            finally:
                resume.set()
            self.assertEqual(first.result(),winner)
        self.assertEqual(self.store.day(D),[winner])

    def test_cached_news_respects_new_topic_and_length_on_outage(self):
        p=prefs()
        old=[{"title":"Model one","topic":"AI models"},{"title":"Robot one","topic":"Robotics"},{"title":"Robot two","topic":"Robotics"}]
        self.store.generate(D,"tech",p,registry={"tech":lambda *_:{"summary":"Model one","stories":old}})
        p["topics"]=["Robotics"];p["length"]=1
        retry={"tech":lambda *_:{"stories":[],"summary":"No matches","gaps":["X unavailable"]}}
        recovered=self.store.generate(D,"tech",p,True,retry)
        self.assertEqual(recovered["stories"],[old[1]])
        self.assertEqual(recovered["summary"],"Robot one")
        p["topics"]=[]
        cleared=self.store.generate(D,"tech",p,True,retry)
        self.assertEqual(cleared["stories"],[])

    def test_transient_error_releases_claim_for_next_tick(self):
        p=prefs()
        def fail(*_):raise OSError("fixture outage")
        with self.assertRaises(OSError):self.store.generate(D,"tech",p,registry={"tech":fail})
        report=self.store.generate(D,"tech",p,registry={"tech":lambda *_:{"summary":"recovered"}})
        self.assertEqual(report,self.store.day(D)[0])
        with closing(self.store.connect()) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM jobs").fetchone()[0],0)
