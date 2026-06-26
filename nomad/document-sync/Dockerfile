FROM astral/uv:python3.13-trixie-slim

WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-cache --project .

COPY sync.py ./

ENTRYPOINT ["uv", "run", "python", "sync.py"]
