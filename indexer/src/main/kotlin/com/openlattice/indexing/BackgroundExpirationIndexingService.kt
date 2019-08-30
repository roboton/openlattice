package com.openlattice.indexing

import com.google.common.base.Stopwatch
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import com.hazelcast.query.QueryConstants
import com.openlattice.conductor.rpc.ConductorElasticsearchApi
import com.openlattice.data.storage.IndexingMetadataManager
import com.openlattice.data.storage.PostgresEntityDataQueryService
import com.openlattice.edm.EntitySet
import com.openlattice.edm.set.ExpirationType
import com.openlattice.edm.type.EntityType
import com.openlattice.edm.type.PropertyType
import com.openlattice.hazelcast.HazelcastMap
import com.openlattice.indexing.configuration.IndexerConfiguration
import com.openlattice.postgres.DataTables.LAST_INDEX
import com.openlattice.postgres.DataTables.LAST_WRITE
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresTable.IDS
import com.openlattice.postgres.ResultSetAdapters
import com.openlattice.postgres.streams.PostgresIterable
import com.openlattice.postgres.streams.StatementHolder
import com.zaxxer.hikari.HikariDataSource
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Function
import java.util.function.Supplier

/**
 *
 */

const val MAX_DURATION_MILLIS = 60000L
const val DATA_DELETION_RATE = 30000L
//const val FETCH_SIZE = 128000

class BackgroundExpirationIndexingService(
        hazelcastInstance: HazelcastInstance,
        private val indexerConfiguration: IndexerConfiguration,
        private val hds: HikariDataSource,
        private val dataQueryService: PostgresEntityDataQueryService,
        private val elasticsearchApi: ConductorElasticsearchApi,
        private val dataManager: IndexingMetadataManager
) {
    companion object {
        private val logger = LoggerFactory.getLogger(BackgroundExpirationIndexingService::class.java)!!
        const val INDEX_SIZE = 1000
    }

    private val propertyTypes: IMap<UUID, PropertyType> = hazelcastInstance.getMap(HazelcastMap.PROPERTY_TYPES.name)
    private val entityTypes: IMap<UUID, EntityType> = hazelcastInstance.getMap(HazelcastMap.ENTITY_TYPES.name)
    private val entitySets: IMap<UUID, EntitySet> = hazelcastInstance.getMap(HazelcastMap.ENTITY_SETS.name)
    private val expirationLocks: IMap<UUID, Long> = hazelcastInstance.getMap(HazelcastMap.EXPIRATION_LOCKS.name)

    //necessary??????
    init {
        expirationLocks.addIndex(QueryConstants.THIS_ATTRIBUTE_NAME.value(), true)
    }

    private val taskLock = ReentrantLock()

    @Suppress("UNCHECKED_CAST", "UNUSED")
    @Scheduled(fixedRate = MAX_DURATION_MILLIS)
    fun scavengeIndexingLocks() {
        expirationLocks.removeAll(
                Predicates.lessThan(
                        QueryConstants.THIS_ATTRIBUTE_NAME.value(),
                        System.currentTimeMillis()
                ) as Predicate<UUID, Long>
        )
    }

    @Suppress("UNUSED")
    @Scheduled(fixedRate = DATA_DELETION_RATE)
    fun deleteExpiredDataFromEntitySets() {
        logger.info("Starting background expired data deletion task.")
        //Keep number of indexing jobs under control
        if (taskLock.tryLock()) {
            try {
                ensureAllEntityTypeIndicesExist()
                if (indexerConfiguration.backgroundExpiredDataDeletionEnabled) {
                    val w = Stopwatch.createStarted()
                    //We shuffle entity sets to make sure we have a chance to work share and index everything
                    val lockedEntitySets = entitySets.values
                            .shuffled()
                            .filter { tryLockEntitySet(it) }
                            .filter { it.name != "OpenLattice Audit Entity Set" } //TODO: Clean out audit entity set from prod

                    val totalIndexed = lockedEntitySets
                            .parallelStream()
                            .filter { !it.isLinking }
                            .mapToInt { indexEntitySetExpirations(it) }
                            .sum()

                    lockedEntitySets.forEach(this::deleteIndexingLock)

                    logger.info(
                            "Completed indexing {} elements in {} ms",
                            totalIndexed,
                            w.elapsed(TimeUnit.MILLISECONDS)
                    )
                } else {
                    logger.info("Skipping background indexing as it is not enabled.")
                }
            } finally {
                taskLock.unlock()
            }
        } else {
            logger.info("Not starting new indexing job as an existing one is running.")
        }
    }

    private fun ensureAllEntityTypeIndicesExist() {
        val existingIndices = elasticsearchApi.entityTypesWithIndices
        val missingIndices = entityTypes.keys - existingIndices
        if (missingIndices.isNotEmpty()) {
            val missingEntityTypes = entityTypes.getAll(missingIndices)
            logger.info("The following entity types were missing indices: {}", missingEntityTypes.keys)
            missingEntityTypes.values.forEach { et ->
                val missingEntityTypePropertyTypes = propertyTypes.getAll(et.properties)
                elasticsearchApi.saveEntityTypeToElasticsearch(
                        et,
                        missingEntityTypePropertyTypes.values.toList()
                )
                logger.info("Created missing index for entity type ${et.type} with id ${et.id}")
            }
        }
    }

    private fun getEntityDataKeysQuery(entitySetId: UUID): String {
        return "SELECT ${ID.name}, ${LAST_WRITE.name} FROM ${IDS.name} " +
                "WHERE ${ENTITY_SET_ID.name} = '$entitySetId' AND ${VERSION.name} > 0"
    }

    private fun getDirtyEntitiesWithLastWriteQuery(entitySetId: UUID): String {
        return "SELECT ${ID.name}, ${LAST_WRITE.name} FROM ${IDS.name} " +
                "WHERE ${ENTITY_SET_ID.name} = '$entitySetId' AND " +
                "${LAST_INDEX.name} < ${LAST_WRITE.name} AND " +
                "${VERSION.name} > 0 " +
                "LIMIT $FETCH_SIZE"
    }

    private fun getEntityDataKeys(entitySetId: UUID): PostgresIterable<Pair<UUID, OffsetDateTime>> {
        return PostgresIterable(Supplier<StatementHolder> {
            val connection = hds.connection
            connection.autoCommit = false
            val stmt = connection.createStatement()
            stmt.fetchSize = 64_000
            val rs = stmt.executeQuery(getEntityDataKeysQuery(entitySetId))
            StatementHolder(connection, stmt, rs)
        }, Function<ResultSet, Pair<UUID, OffsetDateTime>> {
            ResultSetAdapters.id(it) to ResultSetAdapters.lastWriteTyped(it)
        })
    }

    private fun getDirtyEntityKeyIds(entitySetId: UUID): PostgresIterable<Pair<UUID, OffsetDateTime>> {
        return PostgresIterable(Supplier<StatementHolder> {
            val connection = hds.connection
            val stmt = connection.createStatement()
            val rs = stmt.executeQuery(getDirtyEntitiesWithLastWriteQuery(entitySetId))
            StatementHolder(connection, stmt, rs)
        }, Function<ResultSet, Pair<UUID, OffsetDateTime>> {
            ResultSetAdapters.id(it) to ResultSetAdapters.lastWriteTyped(it)
        })
    }

    private fun getPropertyTypeForEntityType(entityTypeId: UUID): Map<UUID, PropertyType> {
        return propertyTypes
                .getAll(entityTypes[entityTypeId]?.properties ?: setOf())
                .filter { it.value.datatype != EdmPrimitiveTypeKind.Binary }
    }

    private fun indexEntitySetExpirations(entitySet: EntitySet): Int {
        logger.info(
                "Starting indexing expired data for entity set {} with id {}",
                entitySet.name,
                entitySet.id
        )

        val esw = Stopwatch.createStarted()
        val timeUntilExpiration = entitySet.expiration.timeToExpiration
        var deletionCount = 0

        when(entitySet.expiration.expirationFlag) {
            //TODO also delete entity key id from ids table and entity from elasticsearch
            //TODO add check that same number of items were deleted from all locations
            //if provided datetime property was longer ago than timeUntilExpiration, delete it
            ExpirationType.DATE_PROPERTY -> {
                val propertyTypeId = entitySet.expiration.startDateProperty.get()
                val propertyType = propertyTypes[propertyTypeId]
                when {
                    propertyType!!.datatype == EdmPrimitiveTypeKind.Date -> {
                        val elapsedTime = OffsetDateTime.ofInstant(Instant.now().minusMillis(timeUntilExpiration), ZoneId.systemDefault()).toLocalDate()
                        deletionCount = deleteExpiredDataByDate(entitySet.id, propertyTypeId, elapsedTime)
                    }
                    propertyType!!.datatype == EdmPrimitiveTypeKind.DateTimeOffset -> {
                        val elapsedTime = OffsetDateTime.ofInstant(Instant.now().minusMillis(timeUntilExpiration), ZoneId.systemDefault())
                        deletionCount = deleteExpiredDataByDateTimeOffset(entitySet.id, propertyTypeId, elapsedTime)
                    }
                    else -> {
                        //something went wrong because you shouldn't be able to use a property type as a expiration marker without providing which one to use
                    }
                }
            }
            //if the initial write was longer ago than timeUntilExpiration, delete it
            ExpirationType.FIRST_WRITE -> {
                val elapsedTime = Instant.now().minusMillis(timeUntilExpiration).toEpochMilli()
                deletionCount = deleteExpiredDataByFirstWrite(entitySet.id, elapsedTime)
            }
            //if it hasn't been updated in longer than the timeUntilExpiration, delete it
            ExpirationType.LAST_WRITE -> {
                val elapsedTime = OffsetDateTime.ofInstant(Instant.now().minusMillis(timeUntilExpiration), ZoneId.systemDefault())
                deletionCount = deleteExpiredDataByLastWrite(entitySet.id, elapsedTime)
                }
            else -> logger.info( "No data has expired.")
        }

        val entityKeyIdsWithLastWrite = if (reindexAll) {
            getEntityDataKeys(entitySet.id)
        } else {
            getDirtyEntityKeyIds(entitySet.id)
        }

        val propertyTypes = getPropertyTypeForEntityType(entitySet.entityTypeId)

        var indexCount = 0
        var entityKeyIdsIterator = entityKeyIdsWithLastWrite.iterator()

        while (entityKeyIdsIterator.hasNext()) {
            updateExpiration(entitySet)
            while (entityKeyIdsIterator.hasNext()) {
                val batch = getBatch(entityKeyIdsIterator)
                indexCount += indexEntities(entitySet, batch, propertyTypes, !reindexAll)
            }
            entityKeyIdsIterator = entityKeyIdsWithLastWrite.iterator()
        }

        logger.info(
                "Finished indexing {} elements from entity set {} in {} ms",
                deletionCount,
                entitySet.name,
                esw.elapsed(TimeUnit.MILLISECONDS)
        )

        return indexCount
    }

    private fun deleteExpiredDataByDate(entitySetId: UUID, propertyTypeId: UUID, elapsedTime: LocalDate): Int {
        val connection = hds.connection
        connection.autoCommit = false
        val stmt = connection.createStatement()
        return stmt.executeUpdate(deleteExpiredDataByDatePropertyQuery(entitySetId, propertyTypeId, elapsedTime.toString()))
    }

    private fun deleteExpiredDataByDateTimeOffset(entitySetId: UUID, propertyTypeId: UUID, elapsedTime: OffsetDateTime): Int {
        val connection = hds.connection
        connection.autoCommit = false
        val stmt = connection.createStatement()
        return stmt.executeUpdate(deleteExpiredDataByDatePropertyQuery(entitySetId, propertyTypeId, elapsedTime.toString()))
    }

    private fun deleteExpiredDataByLastWrite(entitySetId: UUID, elapsedTime: OffsetDateTime): Int {
        val connection = hds.connection
        connection.autoCommit = false
        val stmt = connection.createStatement()
        return stmt.executeUpdate(deleteExpiredDataByLastWriteQuery(entitySetId, elapsedTime.toString()))
    }

    private fun deleteExpiredDataByFirstWrite(entitySetId: UUID, elapsedTime: Long): Int {
        val connection = hds.connection
        connection.autoCommit = false
        val stmt = connection.createStatement()
        return stmt.executeUpdate(deleteExpiredDataByFirstWriteQuery(entitySetId, elapsedTime))
    }

    private fun deleteExpiredDataByDatePropertyQuery(entitySetId: UUID, propertyTypeId: UUID, elapsedTime: String): String {
        return "DELETE FROM DATA WHERE ${ENTITY_SET_ID.name} = '$entitySetId' AND $propertyTypeId < '$elapsedTime'"
    }

    private fun deleteExpiredDataByLastWriteQuery(entitySetId: UUID, elapsedTime: String): String {
        return "DELETE FROM DATA WHERE ${ENTITY_SET_ID.name} = '$entitySetId' AND ${LAST_WRITE.name} < '$elapsedTime'"
    }

    private fun deleteExpiredDataByFirstWriteQuery(entitySetId: UUID, elapsedTime: Long): String {
        return "DELETE FROM DATA WHERE ${ENTITY_SET_ID.name} = '$entitySetId' AND ${VERSIONS.name}[1] < $elapsedTime"
    }

    internal fun indexEntities(
            entitySet: EntitySet,
            batchToIndex: Map<UUID, OffsetDateTime>,
            propertyTypeMap: Map<UUID, PropertyType>,
            markAsIndexed: Boolean = true
    ): Int {
        val esb = Stopwatch.createStarted()
        val entitiesById = dataQueryService.getEntitiesWithPropertyTypeIds(
                mapOf(entitySet.id to Optional.of(batchToIndex.keys)),
                mapOf(entitySet.id to propertyTypeMap)).toMap()

        logger.info("Loading data for indexEntities took {} ms", esb.elapsed(TimeUnit.MILLISECONDS))

        if (entitiesById.size != batchToIndex.size) {
            logger.error(
                    "Expected {} items to index but received {}. Marking as indexed to prevent infinite loop.",
                    batchToIndex.size,
                    entitiesById.size
            )
        }

        val indexCount: Int

        if (entitiesById.isNotEmpty() &&
                elasticsearchApi.createBulkEntityData(entitySet.entityTypeId, entitySet.id, entitiesById)) {
            indexCount = if (markAsIndexed) {
                dataManager.markAsIndexed(mapOf(entitySet.id to batchToIndex), false)
            } else {
                batchToIndex.size
            }

            logger.info(
                    "Indexed batch of {} elements for {} ({}) in {} ms",
                    indexCount,
                    entitySet.name,
                    entitySet.id,
                    esb.elapsed(TimeUnit.MILLISECONDS)
            )
        } else {
            indexCount = 0
            logger.error("Failed to index elements with entitiesById: {}", entitiesById)

        }
        return indexCount

    }

    private fun tryLockEntitySet(entitySet: EntitySet): Boolean {
        return expirationLocks.putIfAbsent(entitySet.id, System.currentTimeMillis() + MAX_DURATION_MILLIS) == null
    }

    private fun deleteIndexingLock(entitySet: EntitySet) {
        expirationLocks.delete(entitySet.id)
    }

    private fun updateExpiration(entitySet: EntitySet) {
        expirationLocks.set(entitySet.id, System.currentTimeMillis() + MAX_DURATION_MILLIS)
    }

    private fun getBatch(entityKeyIdStream: Iterator<Pair<UUID, OffsetDateTime>>): Map<UUID, OffsetDateTime> {
        val entityKeyIds = HashMap<UUID, OffsetDateTime>(INDEX_SIZE)

        var i = 0
        while (entityKeyIdStream.hasNext() && i < INDEX_SIZE) {
            val entityWithLastWrite = entityKeyIdStream.next()
            entityKeyIds[entityWithLastWrite.first] = entityWithLastWrite.second
            ++i
        }

        return entityKeyIds
    }
}