FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

WORKDIR /app
COPY pyproject.toml ./
RUN pip install --no-cache-dir .
COPY src ./src

EXPOSE 8080
CMD ["uvicorn", "application.main:app", "--host", "0.0.0.0", "--port", "8080"]

