package com.example.wspoc.console

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** 브라우저 테스트 콘솔(D23): ws-backend / ws-chat의 각 케이스를 게이트웨이 경유로 눌러 보는 화면. */
@SpringBootApplication
@ConfigurationPropertiesScan
class WsConsoleApplication

fun main(args: Array<String>) {
    runApplication<WsConsoleApplication>(*args)
}
