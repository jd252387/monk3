FROM astral/uv:python3.13-trixie-slim

WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-cache --project .

COPY app.py ./

ENV DOCUMENTS_DIR=/documents

CMD ["uv", "run", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "3000"]
