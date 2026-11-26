"""Exercise progressive station rendering without any external network requests."""
from datetime import date
from pathlib import Path
import sys

from playwright.sync_api import sync_playwright

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from tides import read_station_table


def main():
    requested = date(2026, 11, 2)
    with sync_playwright() as engine:
        browser = engine.chromium.launch()
        try:
            page = browser.new_page(locale="en-GB", timezone_id="UTC")
            page.set_content("""
                <h3>Mohammedia (Maroc)</h3>
                <div class="chart-label"></div>
                <table><thead><tr><th class="tide-title">Monday November 02, 2026</th></tr></thead>
                <tbody></tbody></table>
                <script>
                setTimeout(() => {
                    document.querySelector('tbody').innerHTML =
                      '<tr><td>HW</td><td>06:10</td><td>2.71</td></tr>' +
                      '<tr><td>LW</td><td>12:25</td><td>1.14</td></tr>';
                }, 100);
                setTimeout(() => { document.querySelector('.chart-label').textContent = 'Hour UTC+0'; }, 350);
                </script>""")
            rows = read_station_table(page, requested, "Africa/Casablanca")
            assert [(r["at"], r["type"]) for r in rows] == [
                ("2026-11-02T07:10", "high"), ("2026-11-02T13:25", "low")]
            print("Delayed station rows and UTC label verified in Chromium.")
        finally:
            browser.close()


if __name__ == "__main__":
    main()
