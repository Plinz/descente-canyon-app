# Image of the API and of the scheduled jobs (same code, different commands).
FROM python:3.13-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /opt/dcserver
COPY requirements.lock ./
RUN pip install -r requirements.lock
COPY pyproject.toml ./
COPY dcserver ./dcserver
RUN pip install --no-deps .

# The repository mounted at /srv/dc/repo belongs to the host user running the jobs.
RUN git config --system --add safe.directory /srv/dc/repo

USER 1000:1000
ENTRYPOINT ["dcserver"]
CMD ["api", "--host", "0.0.0.0", "--port", "8080"]
