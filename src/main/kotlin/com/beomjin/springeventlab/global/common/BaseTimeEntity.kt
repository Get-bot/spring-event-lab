package com.beomjin.springeventlab.global.common

import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.LastModifiedDate
import java.time.Instant

@MappedSuperclass
abstract class BaseTimeEntity : BaseCreatedTimeEntity() {
    @LastModifiedDate
    var updatedAt: Instant? = null
        protected set
}
