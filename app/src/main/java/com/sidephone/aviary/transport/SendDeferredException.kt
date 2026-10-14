package com.sidephone.aviary.transport

/** A server-directed wait must not exhaust the automatic retry budget. */
internal class SendDeferredException(val retryAt: Long) : Exception("Signal rate limited this recipient; delivery will retry after the cooldown")
