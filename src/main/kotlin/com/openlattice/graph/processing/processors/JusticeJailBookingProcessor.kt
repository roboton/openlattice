package com.openlattice.graph.processing.processors

import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.time.temporal.ChronoUnit

//@Component
class JusticeJailBookingProcessor: DurationProcessor() {

    override fun getSql(): String {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getHandledEntityType(): String {
        return "justice.JailBooking"
    }

    override fun getPropertyTypeForStart(): String {
        return "publicsafety.datebooked"
    }

    override fun getPropertyTypeForEnd(): String {
        return "ol.datetime_released"
    }

    override fun getPropertyTypeForDuration(): String {
        return "ol.durationdays"
    }

    override fun getCalculationTimeUnit(): ChronoUnit {
        return ChronoUnit.HOURS
    }

    override fun getDisplayTimeUnit(): ChronoUnit {
        return ChronoUnit.DAYS
    }
}