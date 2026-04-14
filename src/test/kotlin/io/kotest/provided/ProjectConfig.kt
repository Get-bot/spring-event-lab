package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.IsolationMode
import io.kotest.engine.concurrency.SpecExecutionMode

object ProjectConfig : AbstractProjectConfig() {
    override val isolationMode = IsolationMode.SingleInstance
    override val specExecutionMode = SpecExecutionMode.Sequential
}
