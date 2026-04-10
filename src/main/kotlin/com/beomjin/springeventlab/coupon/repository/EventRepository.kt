package com.beomjin.springeventlab.coupon.repository

import com.beomjin.springeventlab.coupon.entity.Event
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EventRepository : JpaRepository<Event, UUID>
