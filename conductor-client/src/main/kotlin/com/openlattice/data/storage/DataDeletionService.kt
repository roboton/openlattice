package com.openlattice.data.storage

import com.codahale.metrics.annotation.Timed
import com.geekbeast.rhizome.jobs.HazelcastJobService
import com.openlattice.authorization.*
import com.openlattice.controllers.exceptions.ForbiddenException
import com.openlattice.data.DataDeletionManager
import com.openlattice.data.DeleteType
import com.openlattice.data.WriteEvent
import com.openlattice.data.jobs.DataDeletionJob
import com.openlattice.data.jobs.DataDeletionJobState
import com.openlattice.data.jobs.EntitiesAndNeighborsDeletionJob
import com.openlattice.data.jobs.EntitiesAndNeighborsDeletionJobState
import com.openlattice.data.storage.partitions.PartitionManager
import com.openlattice.datastore.services.EntitySetManager
import com.openlattice.edm.set.EntitySetFlag
import com.openlattice.edm.type.PropertyType
import com.openlattice.graph.core.GraphService
import com.openlattice.search.requests.EntityNeighborsFilter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/*
 * The general approach when deleting (hard or soft delete) entities is as follows:
 *
 * 1. Ensure the user has permissions to delete from the requested entity set.
 * 2. If not deleting from an association entity set, load all association entity set ids that are present in edges of
 *    the entities being deleted, and ensure the user is authorized to delete from all of those association entity sets.
 * 3. Delete all edges connected to the entities being deleted.
 * 4. If not deleting from an association entity set, also delete the association entities from all edges connected to
 *    the entities being deleted.
 * 5. Delete the entities themselves.
 *
 * We could end up with a zombie edge if a user creates an association between one of the deleted entities using a new
 * association entity set after the association auth checks are done. This theoretically shouldn't break anything,
 * but at some point we may want to introduce some kind of locking to prevent this behavior.
 *
 */

@Service
class DataDeletionService(
        private val entitySetManager: EntitySetManager,
        private val authorizationManager: AuthorizationManager,
        private val eds: EntityDatastore,
        private val graphService: GraphService,
        private val jobService: HazelcastJobService,
        private val partitionManager: PartitionManager
) : DataDeletionManager {

    companion object {
        @JvmStatic
        val PERMISSIONS_FOR_DELETE_TYPE = mapOf(
                DeleteType.Soft to EnumSet.of(Permission.WRITE),
                DeleteType.Hard to EnumSet.of(Permission.OWNER)
        )
    }

    private val logger = LoggerFactory.getLogger(DataDeletionService::class.java)

    /** Delete all entities from an entity set **/

    @Timed
    override fun clearOrDeleteEntitySet(entitySetId: UUID, deleteType: DeleteType): UUID {
        val partitions = partitionManager.getEntitySetPartitions(entitySetId)

        return jobService.submitJob(DataDeletionJob(DataDeletionJobState(
                entitySetId,
                deleteType,
                partitions
        )))

    }

    @Timed
    override fun clearOrDeleteEntities(entitySetId: UUID, entityKeyIds: MutableSet<UUID>, deleteType: DeleteType): UUID {
        val partitions = partitionManager.getEntitySetPartitions(entitySetId)

        return jobService.submitJob(DataDeletionJob(DataDeletionJobState(
                entitySetId,
                deleteType,
                partitions,
                entityKeyIds
        )))
    }

    /**
     * Delete property values from specific entities. No entities are actually deleted here.
     */
    @Timed
    override fun clearOrDeleteEntityProperties(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            deleteType: DeleteType,
            propertyTypeIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {

        val writeEvent = if (deleteType === DeleteType.Hard) {
            eds.deleteEntityProperties(entitySetId, entityKeyIds, authorizedPropertyTypes)
        } else {
            eds.clearEntityProperties(entitySetId, entityKeyIds, authorizedPropertyTypes)
        }

        logger.info(
                "Deleted properties {} of {} entities.",
                authorizedPropertyTypes.values.map(PropertyType::getType), writeEvent.numUpdates
        )

        return writeEvent

    }

    /* Authorization checks */

    @Timed
    override fun authCheckForEntitySetsAndNeighbors(entitySetIds: Set<UUID>, deleteType: DeleteType, principals: Set<Principal>) {
        val associationEntitySets = entitySetIds.filter { entitySetManager.getEntitySet(it)!!.flags.contains(EntitySetFlag.ASSOCIATION) }.toSet()

        val srcDstEntitySets = entitySetIds - associationEntitySets

        val authorizedEdgeEntitySets = mutableSetOf<UUID>() + entitySetManager.getAuthorizedNeighborEntitySets(
                principals,
                srcDstEntitySets,
                EntityNeighborsFilter(srcDstEntitySets)
        ).associationEntitySetIds.get()

        val entitySetPropertyTypes = entitySetManager.getPropertyTypesOfEntitySets(authorizedEdgeEntitySets + entitySetIds)

        val requiredPermissions = PERMISSIONS_FOR_DELETE_TYPE.getValue(deleteType)
        authorizationManager.accessChecksForPrincipals(entitySetPropertyTypes.flatMap { (entitySetId, propertyTypes) ->
            propertyTypes.keys.map { ptId ->
                AccessCheck(AclKey(entitySetId, ptId), requiredPermissions)
            }
        }.toSet(), principals).forEach { authorization ->
            requiredPermissions.forEach { permission ->
                if (!authorization.permissions.getValue(permission)) {
                    val unauthorizedEntitySetId = authorization.aclKey.first()
                    if (entitySetIds.contains(unauthorizedEntitySetId)) {
                        throw ForbiddenException("Unable to perform delete on entity set $unauthorizedEntitySetId because " +
                                "$requiredPermissions permissions are required on all its property types.")
                    }
                }
            }
        }

        // Unnecessary since we have already access checked all potential entity sets
//        if (graphService.checkForUnauthorizedEdges(entitySetId, authorizedEdgeEntitySets, entityKeyIds)) {
//            throw ForbiddenException("Unable to perform delete on entity set $entitySetId -- delete would have required permissions on unauthorized edge entity sets.")
//        }
    }

    override fun clearOrDeleteEntitiesAndNeighbors(
            entitySetIdEntityKeyIds: Map<UUID, Set<UUID>>,
            entitySetId: UUID,
            allEntitySetIds: Set<UUID>,
            filter: EntityNeighborsFilter,
            deleteType: DeleteType): UUID {

        val partitions = partitionManager.getPartitionsByEntitySetId(allEntitySetIds)

        return jobService.submitJob(EntitiesAndNeighborsDeletionJob(EntitiesAndNeighborsDeletionJobState(
                entitySetIdEntityKeyIds.toMutableMap(),
                entitySetId,
                filter,
                deleteType,
                partitions,
                mutableSetOf()
        )))
    }
}
