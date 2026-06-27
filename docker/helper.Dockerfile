FROM alpine:3.20
RUN apk add --no-cache curl jq bash bc
COPY scripts/ /scripts/
WORKDIR /scripts
