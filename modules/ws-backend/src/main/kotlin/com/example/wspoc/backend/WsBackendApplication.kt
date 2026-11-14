package com.example.wspoc.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WsBackendApplication

fun main(args: Array<String>) {
    runApplication<WsBackendApplication>(*args)
}
