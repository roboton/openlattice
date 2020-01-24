package com.openlattice.shuttle

import com.fasterxml.jackson.annotation.JsonProperty
import com.openlattice.IdConstants
import com.openlattice.client.RetrofitFactory
import com.openlattice.client.serialization.SerializationConstants
import com.openlattice.mapstores.TestDataFactory
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 *
 * Represents a data integration, including all fields required to run the integration
 *
 * @param key a unique ID used for authorizing a call to run an integration
 * @param environment the retrofit environment (e.g. production, local)
 * @param s3bucket the url of the s3bucket to be used
 * @param contacts the set of email addresses of those responsible for the integration
 * @param organizationId the id of the organization that owns the integration
 * @param logEntitySetId the id of the entity set that stores the logs for this integration
 * @param maxConnections maximum number of connections to postgres allowed for this integration
 * @param callbackUrls urls to receive a POST when integration has completed
 * @param flightPlanParameters a map from [Flight] name to [FlightPlanParameters]
 */
data class Integration(
        @JsonProperty(SerializationConstants.KEY_FIELD) var key: UUID?,
        @JsonProperty(SerializationConstants.ENVIRONMENT) var environment: RetrofitFactory.Environment,
        @JsonProperty(SerializationConstants.S3_BUCKET) var s3bucket: String,
        @JsonProperty(SerializationConstants.CONTACTS) var contacts: Set<String>,
        @JsonProperty(SerializationConstants.ORGANIZATION_ID) var organizationId: UUID = IdConstants.GLOBAL_ORGANIZATION_ID.id,
        @JsonProperty(SerializationConstants.ENTITY_SET_ID) var logEntitySetId: Optional<UUID>,
        @JsonProperty(SerializationConstants.CONNECTIONS) var maxConnections: Optional<Int>,
        @JsonProperty(SerializationConstants.CALLBACK) var callbackUrls: Optional<List<String>>,
        @JsonProperty(SerializationConstants.FLIGHT_PLAN_PARAMETERS) var flightPlanParameters: MutableMap<String, FlightPlanParameters>
) {
    init {
        if (key == null) key = UUID.randomUUID()
    }

    companion object {

        @JvmStatic
        fun testData(): Integration {
            val key = UUID.randomUUID()
            val environment = RetrofitFactory.Environment.LOCAL
            val s3bucket = TestDataFactory.random(10)
            val contacts = setOf<String>(TestDataFactory.random(5))
            val organizationId = UUID.randomUUID()
            return Integration(
                    key,
                    environment,
                    s3bucket,
                    contacts,
                    organizationId,
                    Optional.empty(),
                    Optional.of(5),
                    Optional.empty(),
                    mutableMapOf(TestDataFactory.randomAlphanumeric(5) to FlightPlanParameters.testData())
            )
        }

    }

}