FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .
RUN mkdir -p /app/data
ENV GAME_FEED_DB=/app/data/game-feed.sqlite3
CMD ["python", "-m", "game_feed.server"]
