package com.openlattice.codex

import com.auth0.json.mgmt.users.User
import com.google.common.collect.Range
import com.google.common.collect.Sets
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IMap
import com.openlattice.apps.AppConfigKey
import com.openlattice.apps.AppTypeSetting
import com.openlattice.authorization.HazelcastAclKeyReservationService
import com.openlattice.client.serialization.SerializationConstants
import com.openlattice.controllers.exceptions.BadRequestException
import com.openlattice.data.*
import com.openlattice.datastore.services.EdmManager
import com.openlattice.edm.type.PropertyType
import com.openlattice.hazelcast.HazelcastMap
import com.openlattice.hazelcast.HazelcastQueue
import com.openlattice.organizations.HazelcastOrganizationService
import com.openlattice.organizations.roles.SecurePrincipalsManager
import com.openlattice.postgres.DataTables
import com.openlattice.postgres.PostgresColumn.CLASS_NAME
import com.openlattice.postgres.PostgresColumn.CLASS_PROPERTIES
import com.openlattice.postgres.PostgresTable.SCHEDULED_TASKS
import com.openlattice.postgres.ResultSetAdapters
import com.openlattice.postgres.streams.BasePostgresIterable
import com.openlattice.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.scheduling.ScheduledTask
import com.openlattice.twilio.TwilioConfiguration
import com.twilio.Twilio
import com.twilio.rest.api.v2010.account.Message
import com.twilio.rest.api.v2010.account.message.Media
import com.twilio.type.PhoneNumber
import com.zaxxer.hikari.HikariDataSource
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.stream.Stream
import javax.servlet.http.HttpServletRequest

@Service
class CodexService(
        val twilioConfiguration: TwilioConfiguration,
        val hazelcast: HazelcastInstance,
        val edmManager: EdmManager,
        val dataGraphManager: DataGraphManager,
        val entityKeyIdService: EntityKeyIdService,
        val principalsManager: SecurePrincipalsManager,
        val organizations: HazelcastOrganizationService,
        val executor: ListeningExecutorService,
        val hds: HikariDataSource,
        reservations: HazelcastAclKeyReservationService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CodexService::class.java)
        private val encoder: Base64.Encoder = Base64.getEncoder()
    }

    init {

        if (twilioConfiguration.isCodexEnabled) {
            Twilio.init(twilioConfiguration.sid, twilioConfiguration.token)
        }
    }

    val appId = reservations.getId(CodexConstants.APP_NAME)
    val appTypesByFqn = HazelcastMap.APP_TYPES.getMap(hazelcast).toMap().mapKeys { it.value.type }
    val typesByFqn = CodexConstants.AppType.values().associate { it to appTypesByFqn.getValue(it.fqn) }
    val scheduledTasks: IMap<UUID, ScheduledTask> = HazelcastMap.SCHEDULED_TASKS.getMap(hazelcast)
    val appConfigs: IMap<AppConfigKey, AppTypeSetting> = HazelcastMap.APP_CONFIGS.getMap(hazelcast)
    val codexMedia: IMap<UUID, Base64Media> = HazelcastMap.CODEX_MEDIA.getMap(hazelcast)
    val propertyTypesByAppType = typesByFqn.values.associate { it.id to edmManager.getPropertyTypesOfEntityType(it.entityTypeId) }
    val propertyTypesByFqn = propertyTypesByAppType.values.flatMap { it.values }.associate { it.type to it.id }

    val textingExecutor = Executors.newSingleThreadExecutor()
    val feedsExecutor = Executors.newSingleThreadExecutor()

    val twilioQueue = HazelcastQueue.TWILIO.getQueue(hazelcast)
    val feedsQueue = HazelcastQueue.TWILIO_FEED.getQueue(hazelcast)

    val textingExecutorWorker = textingExecutor.execute {

        if (!twilioConfiguration.isCodexEnabled) {
            return@execute
        }

        Stream.generate { twilioQueue.take() }.forEach { (organizationId, messageEntitySetId, messageContents, toPhoneNumbers, senderId, attachment) ->

            try {
                //Not very efficient.
                val phone = organizations.getOrganization(organizationId)!!.smsEntitySetInfo
                        .flatMap { (phoneNumber, _, entitySetIds, _) -> entitySetIds.map { it to phoneNumber } }
                        .toMap()
                        .getValue(messageEntitySetId)

                if (phone == "") {
                    throw BadRequestException("No source phone number set for organization!")
                }

                val callbackPath = "${twilioConfiguration.callbackBaseUrl}${CodexApi.BASE}${CodexApi.INCOMING}/$organizationId${CodexApi.STATUS}"

                toPhoneNumbers.forEach { toPhoneNumber ->
                    val messageCreator = Message
                            .creator(PhoneNumber(toPhoneNumber), PhoneNumber(phone), messageContents)
                            .setStatusCallback(URI.create(callbackPath))

                    if (attachment != null) {
                        messageCreator.setMediaUrl(writeMediaAndGetPath(attachment))
                    }

                    val message = messageCreator.create()
                    processOutgoingMessage(message, organizationId, senderId, attachment)
                }

            } catch (e: Exception) {
                logger.error("Unable to send outgoing message to phone numbers $toPhoneNumbers in entity set $messageEntitySetId for organization $organizationId", e)
            }
        }
    }

    val fromPhone = PhoneNumber(twilioConfiguration.shortCode)
    val feedsExecutorWorker = feedsExecutor.execute {

        if (!twilioConfiguration.isCodexEnabled) {
            return@execute
        }

        Stream.generate { feedsQueue.take() }.forEach { (messageContents, toPhoneNumber) ->
            try {
                Message.creator(PhoneNumber(toPhoneNumber), fromPhone, messageContents).create()
            } catch (e: Exception) {
                logger.error("Unable to send outgoing feed update message to phone number $toPhoneNumber", e)
            }
        }
    }

    fun scheduleOutgoingMessage(messageRequest: MessageRequest) {
        var id = UUID.randomUUID()
        val task = SendCodexMessageTask(messageRequest)
        while (scheduledTasks.putIfAbsent(id, ScheduledTask(id, messageRequest.scheduledDateTime, task)) != null) {
            id = UUID.randomUUID()
        }
    }

    fun writeMediaAndGetPath(base64Media: Base64Media): String {
        var id = UUID.randomUUID()
        while (codexMedia.putIfAbsent(id, base64Media) != null) {
            id = UUID.randomUUID()
        }

        return "${twilioConfiguration.callbackBaseUrl}/datastore/codex/media/$id"
    }

    fun getAndDeleteMedia(id: UUID): Base64Media {
        val media = codexMedia.getValue(id)
        codexMedia.delete(id)
        return media
    }

    fun integrateMessagesFromTwilioAfterLastSync(
            organizationId: UUID,
            phoneNumber: String,
            startDateTime: OffsetDateTime
    ): OffsetDateTime {
        val formattedDateTime = DateTime(org.joda.time.Instant(startDateTime.toInstant().toEpochMilli()))
        val range = Range.greaterThan(formattedDateTime)

        // process outgoing messages
        val latestOutgoingMessage = integrateMessages(
                organizationId,
                Message.reader().setDateSent(range).setFrom(phoneNumber).read().toSet(),
                isOutgoing = true
        )

        // process incoming messages
        val latestIncomingMessage = integrateMessages(
                organizationId,
                Message.reader().setDateSent(range).setTo(phoneNumber).read().toSet(),
                isOutgoing = false
        )

        return if (latestOutgoingMessage.isAfter(latestIncomingMessage)) latestOutgoingMessage else latestIncomingMessage
    }

    fun processOutgoingMessage(message: Message, organizationId: UUID, senderId: String, attatchment: Base64Media?) {

        val senderEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.PEOPLE)
        val sentFromEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.SENT_FROM)
        val messageEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.MESSAGES)

        val sender = principalsManager.getUser(senderId)

        val senderEntityKey = EntityKey(senderEntitySetId, sender.id)
        val messageEntityKey = EntityKey(messageEntitySetId, message.sid)
        val sentFromEntityKey = EntityKey(sentFromEntitySetId, message.sid)

        val idsByEntityKey = entityKeyIdService.getEntityKeyIds(setOf(senderEntityKey, messageEntityKey, sentFromEntityKey))

        dataGraphManager.createEntities(
                senderEntitySetId,
                listOf(getSenderEntity(sender)),
                getPropertyTypes(CodexConstants.AppType.PEOPLE)
        )

        dataGraphManager.createEntities(
                sentFromEntitySetId,
                listOf(getAssociationEntity(formatDateTime(message.dateCreated))),
                getPropertyTypes(CodexConstants.AppType.SENT_FROM)
        )

        dataGraphManager.createAssociations(setOf(DataEdgeKey(
                EntityDataKey(messageEntitySetId, idsByEntityKey.getValue(messageEntityKey)),
                EntityDataKey(senderEntitySetId, idsByEntityKey.getValue(senderEntityKey)),
                EntityDataKey(sentFromEntitySetId, idsByEntityKey.getValue(sentFromEntityKey))
        )))

        integrateMessages(organizationId, setOf(message), isOutgoing = true)
    }

    fun integrateMessages(organizationId: UUID, messages: Set<Message>, isOutgoing: Boolean): OffsetDateTime {
        var latestDateTime = OffsetDateTime.MIN
        val associationAppType = if (isOutgoing) CodexConstants.AppType.SENT_TO else CodexConstants.AppType.SENT_FROM

        val messageEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.MESSAGES)
        val contactEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.CONTACT_INFO)
        val assocEntitySetId = getEntitySetId(organizationId, associationAppType)

        val entitiesByEntityKey = mutableMapOf<EntityKey, Map<UUID, Set<Any>>>()
        val edgesByEntityKey = mutableListOf<Triple<EntityKey, EntityKey, EntityKey>>()

        messages.forEach {
            val messageId = it.sid
            val phoneNumber = if (isOutgoing) it.to else it.from.toString()
            val dateTime = formatDateTime(it.dateCreated)
            if (dateTime.isAfter(latestDateTime)) {
                latestDateTime = dateTime
            }

            val messageEntityKey = EntityKey(messageEntitySetId, messageId)
            val associationEntityKey = EntityKey(assocEntitySetId, messageId)
            val contactEntityKey = EntityKey(contactEntitySetId, phoneNumber)

            entitiesByEntityKey[messageEntityKey] = getMessageEntity(it, phoneNumber, dateTime, isOutgoing)
            entitiesByEntityKey[associationEntityKey] = getAssociationEntity(dateTime)
            entitiesByEntityKey[contactEntityKey] = getContactEntity(phoneNumber)

            edgesByEntityKey.add(Triple(messageEntityKey, associationEntityKey, contactEntityKey))
        }

        val idsByEntityKey = entityKeyIdService.getEntityKeyIds(entitiesByEntityKey.keys)

        val allPropertyTypes = getPropertyTypes(CodexConstants.AppType.MESSAGES) + getPropertyTypes(associationAppType) + getPropertyTypes(CodexConstants.AppType.CONTACT_INFO)

        entitiesByEntityKey.entries.groupBy { it.key.entitySetId }.mapValues {
            it.value.associate { entry -> idsByEntityKey.getValue(entry.key) to entry.value }
        }.forEach { (entitySetId, entities) ->
            dataGraphManager.partialReplaceEntities(entitySetId, entities, allPropertyTypes)
        }

        dataGraphManager.createAssociations(
                edgesByEntityKey.map { triple ->
                    DataEdgeKey(
                            EntityDataKey(messageEntitySetId, idsByEntityKey.getValue(triple.first)),
                            EntityDataKey(assocEntitySetId, idsByEntityKey.getValue(triple.second)),
                            EntityDataKey(contactEntitySetId, idsByEntityKey.getValue(triple.third))
                    )
                }.toSet()
        )

        return latestDateTime
    }

    fun processIncomingMessage(organizationId: UUID, request: HttpServletRequest) {

        val messageId = request.getParameter(CodexConstants.Request.SID.parameter)
        val message = Message.fetcher(messageId).fetch()

        integrateMessages(organizationId, setOf(message), isOutgoing = false)
    }

    fun updateMessageStatus(organizationId: UUID, messageId: String, status: Message.Status) {

        val wasDelivered = when (status) {
            Message.Status.DELIVERED -> true
            Message.Status.UNDELIVERED -> false
            Message.Status.FAILED -> false
            else -> return
        }

        val messageEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.MESSAGES)
        val messageEntityKeyId = entityKeyIdService.getEntityKeyId(messageEntitySetId, messageId)

        dataGraphManager.partialReplaceEntities(
                messageEntitySetId,
                mapOf(messageEntityKeyId to mapOf(getPropertyTypeId(CodexConstants.PropertyType.WAS_DELIVERED) to setOf(wasDelivered))),
                getPropertyTypes(CodexConstants.AppType.MESSAGES)
        )
    }

    fun retrieveMediaAsBaseSixtyFour(mediaUri: String): ListenableFuture<String> {
        return executor.submit(Callable {
            encoder.encodeToString(URL("https://api.twilio.com$mediaUri").readBytes())
        })
    }

    /**
     * Scheduled message workers
     */

    fun getScheduledMessagesForOrganization(organizationId: UUID): Map<UUID, MessageRequest> {
        return BasePostgresIterable(PreparedStatementHolderSupplier(hds, GET_SCHEDULED_MESSAGES_FOR_ORG_SQL) {
            it.setString(1, organizationId.toString())
        }) {
            val task = ResultSetAdapters.scheduledTask(it).task as SendCodexMessageTask
            ResultSetAdapters.id(it) to task.message
        }.toMap()
    }

    fun getScheduledMessagesForOrganizationAndPhoneNumber(organizationId: UUID, phoneNumber: String): Map<UUID, MessageRequest> {
        return BasePostgresIterable(PreparedStatementHolderSupplier(hds, GET_SCHEDULED_MESSAGES_FOR_ORG_AND_PHONE_SQL) {
            it.setString(1, organizationId.toString())
            it.setString(2, DataTables.quote(phoneNumber))
        }) {
            val task = ResultSetAdapters.scheduledTask(it).task as SendCodexMessageTask
            ResultSetAdapters.id(it) to task.message
        }.toMap()
    }

    fun getMessageRequest(scheduledTaskId: UUID): MessageRequest {
        return (scheduledTasks.getValue(scheduledTaskId).task as SendCodexMessageTask).message
    }

    fun deleteScheduledTask(scheduledTaskId: UUID) {
        scheduledTasks.delete(scheduledTaskId)
    }

    /**
     * Formatters
     */

    private fun formatDateTime(dateTime: DateTime): OffsetDateTime {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(dateTime.toInstant().millis), UTC)
    }

    /**
     * Entity mapping helpers
     */

    private fun getMessageEntity(
            message: Message,
            phoneNumber: String,
            dateTime: OffsetDateTime,
            isOutgoing: Boolean
    ): Map<UUID, Set<Any>> {

        val numMedia = message.numMedia.toInt()
        val media = Sets.newLinkedHashSetWithExpectedSize<Map<String, String>>(numMedia)

        if (numMedia > 0) {
            Media.reader(message.sid).read().forEach {
                media.add(mapOf(
                        "content-type" to it.contentType,
                        "data" to retrieveMediaAsBaseSixtyFour(it.uri.toString()).get()))
            }
        }

        return mapOf(
                getPropertyTypeId(CodexConstants.PropertyType.ID) to setOf(message.sid),
                getPropertyTypeId(CodexConstants.PropertyType.DATE_TIME) to setOf(dateTime),
                getPropertyTypeId(CodexConstants.PropertyType.PHONE_NUMBER) to setOf(phoneNumber),
                getPropertyTypeId(CodexConstants.PropertyType.TEXT) to setOf(setOf(message.body)),
                getPropertyTypeId(CodexConstants.PropertyType.IS_OUTGOING) to setOf(isOutgoing),
                getPropertyTypeId(CodexConstants.PropertyType.IMAGE_DATA) to setOf(media)
        )
    }

    private fun getAssociationEntity(dateTime: OffsetDateTime): Map<UUID, Set<Any>> {
        return mapOf(getPropertyTypeId(CodexConstants.PropertyType.DATE_TIME) to setOf(dateTime))
    }

    private fun getContactEntity(phoneNumber: String): Map<UUID, Set<Any>> {
        return mapOf(
                getPropertyTypeId(CodexConstants.PropertyType.PHONE_NUMBER) to setOf(phoneNumber)
        )
    }

    private fun getSenderEntity(user: User): Map<UUID, Set<Any>> {
        return mapOf(
                getPropertyTypeId(CodexConstants.PropertyType.PERSON_ID) to setOf(user.id),
                getPropertyTypeId(CodexConstants.PropertyType.NICKNAME) to setOf(user.email)
        )
    }

    /**
     * EDM + entity set helpers
     */

    private fun getEntitySetId(organizationId: UUID, type: CodexConstants.AppType): UUID {
        val appTypeId = typesByFqn.getValue(type).id
        val ack = AppConfigKey(appId, organizationId, appTypeId)
        return appConfigs[ack]!!.entitySetId
    }

    private fun getPropertyTypes(type: CodexConstants.AppType): Map<UUID, PropertyType> {
        val appTypeId = typesByFqn.getValue(type).id
        return propertyTypesByAppType.getValue(appTypeId)
    }

    private fun getPropertyTypeId(property: CodexConstants.PropertyType): UUID {
        return propertyTypesByFqn.getValue(property.fqn)
    }

    private val GET_SCHEDULED_MESSAGES_FOR_ORG_SQL = "" +
            "SELECT * " +
            "FROM ${SCHEDULED_TASKS.name} " +
            "  WHERE ${CLASS_NAME.name} = '${SendCodexMessageTask::class.java.name}' " +
            "  AND ${CLASS_PROPERTIES.name}->'${SerializationConstants.MESSAGE}'->>'${SerializationConstants.ORGANIZATION_ID}' = ? "

    private val GET_SCHEDULED_MESSAGES_FOR_ORG_AND_PHONE_SQL = "$GET_SCHEDULED_MESSAGES_FOR_ORG_SQL " +
            " AND ${CLASS_PROPERTIES.name}->'${SerializationConstants.MESSAGE}'->'${SerializationConstants.PHONE_NUMBERS}' @> ?::jsonb"

}