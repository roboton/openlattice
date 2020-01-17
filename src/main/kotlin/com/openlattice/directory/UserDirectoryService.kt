/*
 * Copyright (C) 2019. OpenLattice, Inc.
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

package com.openlattice.directory

import com.auth0.json.mgmt.users.User
import com.codahale.metrics.annotation.Timed
import com.google.common.collect.ImmutableMap
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IMap
import com.openlattice.auth0.Auth0TokenProvider
import com.openlattice.authentication.Auth0Configuration
import com.openlattice.client.RetrofitFactory
import com.openlattice.datastore.services.Auth0ManagementApi
import com.openlattice.directory.pojo.Auth0UserBasic
import com.openlattice.hazelcast.HazelcastMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


interface UserDirectoryService {
    @Timed
    fun getAllUsers(): Map<String, User>
    @Timed
    fun getUser(userId: String): User
    //TODO: Switch over to a Hazelcast map to relieve pressure from Auth0
    @Timed
    fun searchAllUsers(searchQuery: String): Map<String, Auth0UserBasic>
}
private val logger = LoggerFactory.getLogger(UserDirectoryService::class.java)
private const val DEFAULT_PAGE_SIZE = 100

@Service
class LocalUserDirectoryService( auth0Configuration: Auth0Configuration ): UserDirectoryService {
    val users = auth0Configuration.users.associateBy { it.id }
    override fun getAllUsers(): Map<String, User> {
        return users
    }

    override fun getUser(userId: String): User {
        return users.getValue(userId)
    }

    override fun searchAllUsers(searchQuery: String): Map<String, Auth0UserBasic> {
        
    }

}

@Service
class Auth0UserDirectoryService(auth0TokenProvider: Auth0TokenProvider, hazelcastInstance: HazelcastInstance) : UserDirectoryService {
    private val users = HazelcastMap.USERS.getMap( hazelcastInstance )

    private var auth0ManagementApi = RetrofitFactory
            .newClient(auth0TokenProvider.managementApiUrl) { auth0TokenProvider.token }
            .create(Auth0ManagementApi::class.java)


    override fun getAllUsers(): Map<String, User> {
        return ImmutableMap.copyOf(users)
    }

    override fun getUser(userId: String): User {
        return users.getValue(userId)
    }

    //TODO: Switch over to a Hazelcast map to relieve pressure from Auth0
    override fun searchAllUsers(searchQuery: String): Map<String, Auth0UserBasic> {
        logger.info("Searching auth0 users with query: $searchQuery")

        var page = 0
        var pageOfUsers = auth0ManagementApi.searchAllUsers(searchQuery, page++, DEFAULT_PAGE_SIZE)
        val users = mutableSetOf<Auth0UserBasic>()

        while (pageOfUsers != null) {
            users.addAll(pageOfUsers)

            if (pageOfUsers.size == DEFAULT_PAGE_SIZE) {
                pageOfUsers = auth0ManagementApi.searchAllUsers(searchQuery, page++, DEFAULT_PAGE_SIZE)
            } else {
                break
            }
        }

        if (users.isEmpty()) {
            logger.warn("Auth0 did not return any users for this search.")
            return mapOf()
        }

        return users.map { it.userId to it }.toMap()
    }
}