ARG BUILD_FROM
FROM $BUILD_FROM

RUN apk add --no-cache python3

COPY mirror.py /mirror.py

CMD ["python3", "-u", "/mirror.py", "serve"]
