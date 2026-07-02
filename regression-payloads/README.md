# Regression Payloads

This folder is the deterministic payload home used by the gateway.

Configured in:

```properties
payload.base.dir=regression-payloads/
payload.default.file=datahub/default-booking-update.json
payload.manifest.file=manifest.csv
```

Usage examples:

```text
/execute/datahub?bookingId=31835146&payload=datahub/default-booking-update.json
/execute/apigee?bookingId=31835146&payload=apigee/package-offers-postman.json
/execute/vrp?bookingId=31835146&payload=vrp/DisplayBooking_v3.xml
/execute/nordics?bookingId=31835146&payload=nordics/booking-event.json
/execute/run?bookingId=31835146&category=KAFKA&flow=DMS_BOOKING&scenario=BOOKINGDETAILS&mode=ASYNC
/execute?bookingId=31835146&category=DATAHUB&flow=DMS_BOOKING&scenario=BOOKING_UPDATE&mode=ASYNC
```

Sample browser URLs:

```text
http://localhost:8080/execute/datahub?bookingId=31835146&async=false&payload=datahub/booking-update-full.json
http://localhost:8080/execute/datahub?bookingId=31835146&async=true&payload=datahub/booking-cancel-event.json

http://localhost:8080/execute/apigee?bookingId=31835146&payload=apigee/package-offers-postman.json
http://localhost:8080/execute/apigee?bookingId=31835146&payload=apigee/flight-offers-postman.json

http://localhost:8080/execute/vrp?bookingId=31835146&payload=vrp/DisplayBooking_v3.xml
http://localhost:8080/execute/vrp?bookingId=31835146&payload=vrp/booking-modify-request.xml

http://localhost:8080/execute/nordics?bookingId=31835146&payload=nordics/booking-created-event.json
http://localhost:8080/execute/nordics?bookingId=31835146&payload=nordics/customer-id-updated-event.json
```

Catalog-based examples:

```text
http://localhost:8080/execute/run?bookingId=31835146&category=DATAHUB&flow=DMS_BOOKING&scenario=BOOKING_UPDATE_FULL&mode=SYNC
http://localhost:8080/execute/run?bookingId=31835146&category=DATAHUB&flow=DMS_BOOKING&scenario=BOOKING_CANCEL&mode=ASYNC
http://localhost:8080/execute/run?bookingId=31835146&category=APIGEE&flow=DMS_BOOKING&scenario=PACKAGEOFFERS&mode=SYNC
http://localhost:8080/execute/run?bookingId=31835146&category=APIGEE&flow=DMS_BOOKING&scenario=FLIGHTOFFERS&mode=SYNC
http://localhost:8080/execute/run?bookingId=31835146&category=VRP&flow=DMS_BOOKING&scenario=DISPLAYBOOKING_V3&mode=SYNC
http://localhost:8080/execute/run?bookingId=31835146&category=VRP&flow=DMS_BOOKING&scenario=BOOKING_MODIFY&mode=SYNC
http://localhost:8080/execute/run?bookingId=31835146&category=NORDICS&flow=DMS_BOOKING&scenario=BOOKING_CREATED&mode=ASYNC
http://localhost:8080/execute/run?bookingId=31835146&category=NORDICS&flow=DMS_BOOKING&scenario=CUSTOMER_ID_UPDATED&mode=ASYNC
http://localhost:8080/execute/run?bookingId=31835146&category=KAFKA&flow=DMS_BOOKING&scenario=BOOKINGDETAILS&mode=ASYNC
http://localhost:8080/execute/run?bookingId=31835146&category=KAFKA&flow=DMS_BOOKING&scenario=PUBLISHBOOKINGDETAILS&mode=ASYNC
```

Rules:

- Keep one payload per regression scenario.
- Use stable names: `<module>/<scenario>.json` or `<module>/<scenario>.xml`.
- Do not place secrets in payload files.
- Payload paths are resolved only inside this folder; `../` paths are rejected.
- To use an absolute external folder instead, change `payload.base.dir`, for example `C:/integration-regression-payloads/`.
- `category + flow + scenario + mode` are resolved through `manifest.csv` when `payload=` is not supplied.
