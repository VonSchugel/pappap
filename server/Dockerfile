FROM mcr.microsoft.com/playwright/python:v1.52.0-noble
WORKDIR /daybook
COPY server/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY server server
COPY app/src/main/assets/defaults.json app/src/main/assets/defaults.json
ENV PYTHONPATH=/daybook/server PYTHONTZPATH="" DB_PATH=/state/daybook.sqlite
CMD ["uvicorn","main:app","--host","0.0.0.0","--port","8000"]
