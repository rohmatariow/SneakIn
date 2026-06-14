package com.sneakin.core

import burp.api.montoya.http.message.responses.HttpResponse

/** A response paired with the wall-clock time its round-trip took. */
class SentResponse(val response: HttpResponse, val timeMs: Long)
