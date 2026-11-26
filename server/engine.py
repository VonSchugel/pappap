import json
import os
import sqlite3
import time
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo
from sources import REPORTS, ROOT, iso

DEFAULTS = ROOT / "app/src/main/assets/defaults.json"

class Store:
    def __init__(self, path=None):
        self.path = str(path or os.getenv("DB_PATH","daybook.sqlite"))
        with closing(self.connect()) as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS prefs(id INTEGER PRIMARY KEY, body TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS reports(day TEXT, kind TEXT, body TEXT, PRIMARY KEY(day,kind));
                CREATE TABLE IF NOT EXISTS jobs(day TEXT,kind TEXT,token TEXT,until REAL,PRIMARY KEY(day,kind));
            """)
    def connect(self):
        return sqlite3.connect(self.path,timeout=20,isolation_level=None)
    def prefs(self):
        with closing(self.connect()) as db:
            row=db.execute("SELECT body FROM prefs WHERE id=1").fetchone()
        return json.loads(row[0]) if row else json.loads(DEFAULTS.read_text(encoding="utf8"))
    def save(self,p):
        with closing(self.connect()) as db:
            db.execute("INSERT OR REPLACE INTO prefs VALUES(1,?)",(json.dumps(p),))
    def day(self,day):
        with closing(self.connect()) as db:
            return [json.loads(r[0]) for r in db.execute("SELECT body FROM reports WHERE day=? ORDER BY kind",(day,))]
    def generate(self,day,kind,p,force=False,registry=None):
        producer=(REPORTS if registry is None else registry)[kind]
        token=str(uuid.uuid4()); now=time.time()
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            row=db.execute("SELECT body FROM reports WHERE day=? AND kind=?",(day,kind)).fetchone()
            prev=json.loads(row[0]) if row else None
            lease=db.execute("SELECT until FROM jobs WHERE day=? AND kind=?",(day,kind)).fetchone()
            if (lease and lease[0]>now) or (prev and not force and (not prev.get("gaps") or prev.get("retryAfter",0)>now)):
                db.rollback(); return prev
            db.execute("INSERT OR REPLACE INTO jobs VALUES(?,?,?,?)",(day,kind,token,now+900)); db.commit()
        try:
            result=producer(day,p)
            # A transient loss of every news source must not erase an existing edition.
            if prev and result.get("gaps"):
                for key in ("stories","events"):
                    if prev.get(key) and key in result and not result[key]:
                        retained=prev[key]
                        if key=="stories":
                            retained=[story for story in retained if story.get("topic") in p["topics"]][:p["length"]]
                        if retained:
                            result[key]=retained
                            result["summary"]=retained[0]["title"] if key=="stories" else prev["summary"]
                            result["gaps"].append("Previous successful items retained")
            result.update(kind=kind,title=p["schedule"][kind]["title"],day=day,time=p["schedule"][kind]["time"],updated=iso(),demo=False,retryAfter=now+3600)
            with closing(self.connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                if db.execute("SELECT token FROM jobs WHERE day=? AND kind=?",(day,kind)).fetchone()==(token,):
                    db.execute("INSERT OR REPLACE INTO reports VALUES(?,?,?)",(day,kind,json.dumps(result)))
                else:
                    current=db.execute("SELECT body FROM reports WHERE day=? AND kind=?",(day,kind)).fetchone()
                    result=json.loads(current[0]) if current else None
                db.commit()
            return result
        finally:
            with closing(self.connect()) as db:
                db.execute("DELETE FROM jobs WHERE day=? AND kind=? AND token=?",(day,kind,token))
    def tick(self,now=None,registry=None):
        registry=REPORTS if registry is None else registry
        p=self.prefs(); now=(now or datetime.now(timezone.utc)).astimezone(ZoneInfo(p["zone"]))
        day=now.date().isoformat(); clock=now.strftime("%H:%M")
        result={}
        for k in registry:
            item=p["schedule"].get(k,{})
            if item.get("enabled") and now.isoweekday() in item.get("weekdays",range(1,8)) and item["time"]<=clock:
                try: self.generate(day,k,p,registry=registry); result[k]="checked"
                except Exception as exc: result[k]="retry on next tick · "+type(exc).__name__
        return result

