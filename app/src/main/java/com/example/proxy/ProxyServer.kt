package com.example.proxy

import fi.iki.elonen.NanoHTTPD
import android.util.Log

class ProxyServer(
    private val edgeRegistry: EdgeRegistry,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.i("ProxyServer", "Request to $uri")

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            "{\"message\": \"Proxy active. Found ${edgeRegistry.getAll().size} edges.\"}"
        )
    }
}
