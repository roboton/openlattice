

/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */

package com.openlattice.data;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.SetMultimap;
import com.openlattice.edm.type.PropertyType;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public interface EntityDatastore {

    EntitySetData<FullQualifiedName> getEntitySetData(
            UUID entitySetId,
            LinkedHashSet<String> orderedPropertyNames,
            Set<PropertyType> authorizedPropertyTypes );

    Stream<SetMultimap<FullQualifiedName, Object>> getEntities(
            UUID entitySetId,
            Set<UUID> ids,
            Set<PropertyType> authorizedPropertyTypes );

    ListMultimap<UUID, SetMultimap<FullQualifiedName, Object>> getEntitiesAcrossEntitySets(
            SetMultimap<UUID, UUID> entitySetIdsToEntityKeyIds,
            Map<UUID, Set<PropertyType>> authorizedPropertyTypesByEntitySet );

    SetMultimap<FullQualifiedName, Object> getEntity(
            UUID entitySetId,
            String entityId,
            Set<PropertyType> authorizedPropertyTypes );

    /**
     * Replaces the contents of an entity in its entirety. Equivalent to a delete of the existing entity and write
     * of new values
     */
    void replaceEntity(
            UUID entitySetId,
            UUID entityKeyId,
            SetMultimap<UUID, Object> entity,
            Set<PropertyType> authorizedPropertyTypes );

    /**
     * Replaces a subset of the properties of an entity specified in the provided {@code entity} argument.
     */
    void partialReplaceEntity(
            UUID entitySetId,
            UUID entityKeyId,
            SetMultimap<UUID, Object> entity,
            Set<PropertyType> authorizedPropertyTypes );

    /**
     * Replace specific values in an entity
     */
    void replaceEntityProperties(
            UUID entitySetId,
            UUID entityKeyId,
            SetMultimap<UUID, Map<ByteBuffer, Object>> replacementProperties,
            Set<PropertyType> authorizedPropertyTypes );

    /**
     * Merges in new entity data without affecting existing entity data.
     */
    void mergeIntoEntity(
            UUID entitySetId,
            UUID entityKeyId,
            SetMultimap<UUID, Object> entity,
            Set<PropertyType> authorizedPropertyTypes );

    /**
     * Clears (soft-deletes) the contents of an entity set by setting version to {@code -now()}
     *
     * @param entitySetId The id of the entity set to clear.
     * @return The number of rows cleared from the entity set.
     */
    int clearEntitySet( UUID entitySetId, Set<PropertyType> authorizedPropertyTypes );

    /**
     * Clears (soft-deletes) the contents of an entity by setting versions of all properties to {@code -now()}
     *
     * @param entitySetId The id of the entity set to clear.
     * @param entityKeyId The entity key id for the entity set to clear.
     * @return The number of properties cleared.
     */
    int clearEntities( UUID entitySetId, Set<UUID> entityKeyId, Set<PropertyType> authorizedPropertyTypes );

    /**
     * Deletes an entity set and removes the historical contents. This causes loss of historical data
     * and should only be used for scrubbing customer data.
     *
     * @param entitySetId The entity set id to be hard deleted.
     */
    int deleteEntitySetData( UUID entitySetId, Set<PropertyType> authorizedPropertyTypes );

    /**
     * Deletes an entity and removes the historical contents.
     *
     * @param entityKeyId The entity key id to be hard deleted.
     */
    int deleteEntities( UUID entitySetId, Set<UUID> entityKeyId, Set<PropertyType> authorizedPropertyTypes );

}
