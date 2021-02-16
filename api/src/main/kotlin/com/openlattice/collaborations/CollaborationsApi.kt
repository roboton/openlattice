package com.openlattice.collaborations

import com.openlattice.organizations.OrganizationDatabase
import retrofit2.http.*
import java.util.*

interface CollaborationsApi {

    companion object {
        // @formatter:off
        const val  SERVICE = "/datastore"
        const val  CONTROLLER = "/collaborations"
        const val  BASE = SERVICE + CONTROLLER
        // @formatter:on

        const val DATABASE_PATH = "/database"
        const val ORGANIZATIONS_PATH = "/organizations"
        const val PROJECT_PATH = "/project"
        const val TABLES_PATH = "/tables"

        const val ID = "id"
        const val ID_PATH = "/{$ID}"
        const val ORGANIZATION_ID = "organizationId"
        const val ORGANIZATION_ID_PATH = "/{$ORGANIZATION_ID}"
        const val TABLE_ID = "tableId"
        const val TABLE_ID_PATH = "/{$TABLE_ID}"
    }

    @GET(BASE)
    fun getCollaborations(): Iterable<Collaboration>

    @POST(BASE)
    fun createCollaboration(@Body collaboration: Collaboration): UUID

    @GET(BASE + ID_PATH)
    fun getCollaboration(@Path(ID) id: UUID): Collaboration

    @GET(BASE + ORGANIZATIONS_PATH + ORGANIZATION_ID_PATH)
    fun getCollaborationsIncludingOrganization(@Path(ORGANIZATION_ID) organizationId: UUID): Iterable<Collaboration>

    @DELETE(BASE + ID_PATH)
    fun deleteCollaboration(@Path(ID) id: UUID)

    @POST(BASE + ID_PATH + ORGANIZATIONS_PATH)
    fun addOrganizationIdsToCollaboration(@Path(ID) id: UUID, @Body organizationIds: Set<UUID>)

    @DELETE(BASE + ID_PATH + ORGANIZATIONS_PATH)
    fun removeOrganizationIdsFromCollaboration(@Path(ID) id: UUID, @Body organizationIds: Set<UUID>)

    @GET(BASE + ID_PATH + DATABASE_PATH)
    fun getCollaborationDatabaseInfo(@Path(ID) id: UUID): OrganizationDatabase

    @PATCH(BASE + ID_PATH + DATABASE_PATH)
    fun renameDatabase(@Path(ID) id: UUID, @Body newDatabaseName: String)

    @GET(BASE + ID_PATH + PROJECT_PATH + ORGANIZATION_ID_PATH + TABLE_ID_PATH)
    fun projectTableToCollaboration(
            @Path(ID) collaborationId: UUID,
            @Path(ORGANIZATION_ID) organizationId: UUID,
            @Path(TABLE_ID) tableId: UUID
    )

    @DELETE(BASE + ID_PATH + PROJECT_PATH + ORGANIZATION_ID_PATH + TABLE_ID_PATH)
    fun removeProjectedTableFromCollaboration(
            @Path(ID) collaborationId: UUID,
            @Path(ORGANIZATION_ID) organizationId: UUID,
            @Path(TABLE_ID) tableId: UUID
    )

    /**
     * Loads all authorized projected tables in an organization
     *
     * @param organizationId The id of the organization to find projected tables for
     * @return A map from collaborationId to all table ids projected in that collaboration.
     */
    @GET(BASE + ORGANIZATIONS_PATH + ORGANIZATION_ID_PATH + TABLES_PATH)
    fun getProjectedTablesInOrganization(@Path(ORGANIZATION_ID) organizationId: UUID): Map<UUID, List<UUID>>

    /**
     * Loads all authorized projected tables in a collaboration
     *
     * @param collaborationId The id of the collaboration to find projected tables for
     * @return A map from organizationId to all table ids projected to the requested collaboration from that organization.
     */
    @GET(BASE + ID_PATH + TABLES_PATH)
    fun getProjectedTablesInCollaboration(@Path(ID) collaborationId: UUID): Map<UUID, List<UUID>>
}