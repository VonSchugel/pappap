#!/usr/bin/env bash
# Run on a disposable CI host. Exercise the imported workflow against the real ASGI service.
set -euo pipefail
export APP_TOKEN
APP_TOKEN=$(python -c 'import secrets;print(secrets.token_urlsafe(32))')
scratch=$(mktemp -d)
chmod 755 "$scratch"
export DB_PATH="$scratch/test.sqlite" PYTHONPATH=server
export N8N_DIAGNOSTICS_ENABLED=false N8N_VERSION_NOTIFICATIONS_ENABLED=false
image=docker.n8n.io/n8nio/n8n:2.48.7
volume="marqicci-n8n-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
instance="marqicci-scheduler-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
python -m uvicorn main:app --port 8000 --host 127.0.0.1 >"$scratch/api.log" 2>&1 &
api_pid=$!
cleanup() {
  docker rm -f "$instance" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  kill "$api_pid" 2>/dev/null || true
}
trap cleanup EXIT
for _ in $(seq 1 30); do
  if curl -fsS -H "Authorization: Bearer $APP_TOKEN" http://127.0.0.1:8000/v1/preferences >"$scratch/prefs.json"; then break; fi
  sleep 1
done
python - "$scratch" <<'PY'
import json, os, sys, uuid
from pathlib import Path
from urllib.request import Request, urlopen
folder=Path(sys.argv[1])
p=json.loads((folder/"prefs.json").read_text())
for k,r in p["schedule"].items():
    r.update(enabled=k=="tech",time="00:00")
p["zone"]="UTC"
p["sources"]={k:False for k in p["sources"]}
auth="Bearer "+os.environ["APP_TOKEN"]
urlopen(Request("http://localhost:8000/v1/preferences",data=json.dumps(p).encode(),method="PUT",headers={"Authorization":auth,"Content-Type":"application/json"})).close()
cred={"id":"daybookCredential","name":"Daybook test","type":"httpHeaderAuth","data":{"name":"Authorization","value":auth}}
(folder/"credential.json").write_text(json.dumps([cred]))
w=json.loads(Path("n8n/Marqicci-Daily.json").read_text())
w.update(id="daybookSchedule",versionId=str(uuid.uuid4()))
node=next(n for n in w["nodes"] if n["type"].endswith("httpRequest"))
node["parameters"]["url"]=node["parameters"]["url"].replace("http://api:","http://127.0.0.1:")
node["credentials"]={"httpHeaderAuth":{"id":cred["id"],"name":cred["name"]}}
(folder/"workflow.json").write_text(json.dumps([w]))
PY
docker volume create "$volume" >/dev/null
opts=(--network host -v "$volume:/home/node/.n8n" -v "$scratch:/fixture:ro" -e N8N_DIAGNOSTICS_ENABLED -e N8N_VERSION_NOTIFICATIONS_ENABLED)
docker run --rm "${opts[@]}" "$image" import:credentials --input=/fixture/credential.json
docker run --rm "${opts[@]}" "$image" import:workflow --input=/fixture/workflow.json
docker run --rm "${opts[@]}" "$image" publish:workflow --id=daybookSchedule
docker run -d --name "$instance" "${opts[@]}" "$image" start >/dev/null
for _ in $(seq 1 32); do
  if curl -fsS -H "Authorization: Bearer $APP_TOKEN" "http://localhost:8000/v1/days/$(date -u +%F)" |
      python -c 'import sys,json;sys.exit([r["kind"] for r in json.load(sys.stdin)["reports"]]!=["tech"])'; then
    echo "Published one Tech Brief through the n8n clock trigger with no Android client."
    exit 0
  fi
  sleep 5
done
docker logs --tail 40 "$instance"
exit 1
