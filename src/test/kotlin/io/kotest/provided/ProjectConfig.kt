package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.IsolationMode
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.extensions.spring.SpringExtension

object ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension())
    override val isolationMode = IsolationMode.SingleInstance
    override val specExecutionMode = SpecExecutionMode.Sequential
}
