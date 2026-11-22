import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

@unittest.skipUnless(importlib.util.find_spec("fastapi") and importlib.util.find_spec("httpx"),"Requires FastAPI and httpx")
class ApiTests(unittest.TestCase):
    def test_api_preferences_and_archive(self):
        from fastapi.testclient import TestClient
        with tempfile.TemporaryDirectory() as tmp,patch.dict(os.environ,{"APP_TOKEN":"integration-test","DB_PATH":str(Path(tmp)/"test.db")}):
            import main
            from engine import Store
            with patch.object(main,"store",Store(os.environ["DB_PATH"])):
                with TestClient(main.app) as client:
                    self.assertEqual(client.get("/v1/preferences").status_code,401)
                    client.headers.update({"Authorization":"Bearer integration-test"})
                    p=client.get("/v1/preferences").json()
                    p["schedule"]["weather"]["time"]="11:18"
                    self.assertEqual(client.put("/v1/preferences",json=p).status_code,200)
                    self.assertEqual(client.get("/v1/preferences").json()["schedule"]["weather"]["time"],"11:18")
                    self.assertEqual(client.post("/v1/days/2000-01-01/refresh/weather").status_code,409)
                    p["swimEnd"]=p["swimStart"]
                    self.assertEqual(client.put("/v1/preferences",json=p).status_code,422)

