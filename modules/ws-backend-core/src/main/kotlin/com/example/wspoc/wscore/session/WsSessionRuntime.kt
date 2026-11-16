package com.example.wspoc.wscore.session

import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.support.drain.DrainState
import com.example.wspoc.wscore.WsProperties
import java.time.Clock

/** 모든 [ManagedWsHandler]가 공유하는 런타임 의존성. 서비스는 이 빈 하나만 주입받으면 된다. */
class WsSessionRuntime(
    val props: WsProperties,
    val registry: SessionRegistry,
    val sessionScope: SessionScope,
    val serverIdentity: ServerIdentity,
    val drainState: DrainState,
    val meters: WsMeters,
    val clock: Clock = Clock.systemUTC(),
)
