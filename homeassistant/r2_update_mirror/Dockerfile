ARG BUILD_FROM
FROM $BUILD_FROM

RUN apk add --no-cache python3

COPY mirror.py hass.py mqtt_lite.py r2robot.py /app/

CMD ["python3", "-u", "/app/mirror.py", "serve"]
