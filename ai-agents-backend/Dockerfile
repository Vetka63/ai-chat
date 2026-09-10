FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

WORKDIR /app
COPY pyproject.toml ./
RUN pip install --no-cache-dir .
COPY scripts/download_tokenizer.py ./scripts/download_tokenizer.py
RUN python scripts/download_tokenizer.py /app/tokenizers/ministral-3b
COPY src ./src
COPY scripts ./scripts

EXPOSE 8080
CMD ["uvicorn", "application.main:app", "--host", "0.0.0.0", "--port", "8080"]
