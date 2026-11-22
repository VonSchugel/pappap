import os
import re
import secrets
from datetime import date, datetime, time
from typing import Literal
from zoneinfo import ZoneInfo
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator
from engine import Store
from sources import REPORTS

def authorized(authorization: str = Header(default="")):
    key=os.getenv("APP_TOKEN","")
    if not key or not secrets.compare_digest(authorization,"Bearer "+key):
        raise HTTPException(401,"A valid app token is required")

app=FastAPI(title="Marqicci Daybook",dependencies=[Depends(authorized)])
store=Store()

class Item(BaseModel):
    title: str=Field(min_length=1,max_length=100)
    time: str=Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    enabled:bool
    notify:bool

class Settings(BaseModel):
    model_config=ConfigDict(extra="forbid")
    zone:str
    lat:float=Field(ge=-90,le=90)
    lon:float=Field(ge=-180,le=180)
    swimStart:str=Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    swimEnd:str=Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    length:int=Field(ge=1,le=40)
    topics:list[Literal["AI models","Developer tools","Robotics"]]=Field(max_length=3)
    repos:list[str]=Field(max_length=20)
    orgs:list[str]=Field(max_length=8)
    accounts:list[str]=Field(max_length=20)
    sources:dict[str,bool]
    schedule:dict[str,Item]
    @model_validator(mode="after")
    def valid(self):
        try: ZoneInfo(self.zone)
        except Exception as e: raise ValueError("Invalid IANA timezone") from e
        if self.swimEnd<=self.swimStart: raise ValueError("Window must end later on the same day")
        for values,pattern in [(self.repos,r"[\w.-]+/[\w.-]+"),(self.orgs,r"[\w-]+"),(self.accounts,r"[A-Za-z0-9_]{1,15}")]:
            if any(not re.fullmatch(pattern,v) or ".." in v for v in values): raise ValueError("Invalid follow list")
        if not set(REPORTS)|{"journal"} <= set(self.schedule): raise ValueError("Missing recurring items")
        return self

@app.get("/v1/preferences")
def preferences(): return store.prefs()

@app.put("/v1/preferences")
def update(value:Settings):
    p=value.model_dump(); store.save(p); return p

@app.post("/v1/tick")
def tick(): return store.tick()

@app.get("/v1/days/{day}")
def day_book(day:date): return {"date":day,"reports":store.day(day.isoformat())}

@app.post("/v1/days/{day}/refresh/{kind}")
def refresh(day:date,kind:str):
    p=store.prefs()
    if kind not in REPORTS: raise HTTPException(404,"Unknown report")
    if day!=datetime.now(ZoneInfo(p["zone"])).date(): raise HTTPException(409,"Archived editions are read-only")
    try: result=store.generate(day.isoformat(),kind,p,force=True)
    except Exception as exc: raise HTTPException(503,"Refresh failed. Last successful report retained.") from exc
    if result is None: raise HTTPException(409,"Generation in progress. Sync again shortly.")
    return result

