package com.hcl.observability.trace;

public enum TraceProtocol {
    HTTP,
    REST,
    JMS,
    SOAP_HTTP,
    SOAP_JMS,
    RABBITMQ,
    KAFKA,
    SFTP_LOG,
    INTERNAL,
    UNKNOWN
}
