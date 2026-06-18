package com.hcl.observability.trace;

public enum TracePhase {
    REQUEST,
    REPLY,
    SEND,
    ACK,
    CONSUME,
    PROCESS,
    NOTIFY,
    PUBLISH,
    CONFIRM,
    ERROR,
    UNKNOWN
}
