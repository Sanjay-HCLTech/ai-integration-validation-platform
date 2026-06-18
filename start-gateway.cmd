@echo off
set ROOT=%~dp0
cd /d "%ROOT%"
"C:\Program Files (x86)\Tools_Heckathon\apache-maven-3.9.12\bin\mvn.cmd" -pl integration-api-gateway -am spring-boot:run >> "%ROOT%gateway-server.out.log" 2>> "%ROOT%gateway-server.err.log"
