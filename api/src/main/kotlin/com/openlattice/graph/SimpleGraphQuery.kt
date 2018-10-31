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
 *
 */

package com.openlattice.graph

import com.openlattice.analysis.requests.Filter
import java.util.*

/**
 * This class is a simple filter based graph query data class. The current limitation of this class is that it doesn't
 * operate on entity types. The caller is responsible for specifying the entity sets to operate on.
 *  
 * @param vertexFilters Defines constraints on vertices in the graph. A list of maps from entity set to property type id.
 * @param edgeFilters
 */
data class SimpleGraphQuery(
        val entityConstraints: List<GraphEntityConstraint>,
        val associationConstraints: List<SimpleAssociationQuery>
)