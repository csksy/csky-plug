# Eera bridge - Docker image
# Build:  docker build -t eera-bridge .
# Run:    docker run -p 8000:8000 -e API_ID=... -e API_HASH=... eera-bridge
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

ENV PORT=8000
EXPOSE 8000

CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port $PORT"]
