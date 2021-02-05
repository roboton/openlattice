package com.openlattice.assembler

import com.hazelcast.map.IMap
import com.openlattice.authorization.AccessTarget
import com.openlattice.authorization.AclKey
import com.openlattice.authorization.Permission
import com.openlattice.authorization.PrincipalType
import com.openlattice.authorization.processors.GetOrCreateExternalPermissionRoleNameEntryProcessor
import com.openlattice.postgres.TableColumn
import java.util.*

private const val INTERNAL_PREFIX = "ol-internal"

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class PostgresRoles private constructor() {
    companion object {

        @JvmStatic
        fun getOrCreatePermissionRole(
                externalRoleNames: IMap<AccessTarget, UUID>,
                tableId: UUID,
                columnId: UUID,
                permission: Permission
        ): String {
            return getOrCreatePermissionRole(externalRoleNames, AccessTarget.forPermissionOnTarget(permission, tableId, columnId))
        }

        @JvmStatic
        fun getOrCreatePermissionRole( permissionRoleNames: IMap<AccessTarget, UUID>, column: TableColumn, permission: Permission): String {
            return getOrCreatePermissionRole(permissionRoleNames, AccessTarget.forPermissionOnTarget(permission, column.tableId, column.columnId))
        }

        @JvmStatic
        fun getOrCreatePermissionRole( permissionRoleNames: IMap<AccessTarget, UUID>, permission: Permission, root: UUID, vararg parts: UUID ): String {
            return getOrCreatePermissionRole(permissionRoleNames, AccessTarget.forPermissionOnTarget(permission, root, *parts))
        }

        @JvmStatic
        fun getOrCreatePermissionRole( permissionRoleNames: IMap<AccessTarget, UUID>, target: AccessTarget ): String {
            return permissionRoleNames.executeOnKey(target, GetOrCreateExternalPermissionRoleNameEntryProcessor())
        }

        @JvmStatic
        fun buildOrganizationUserId(organizationId: UUID): String {
            return "$INTERNAL_PREFIX|organization|$organizationId"
        }

        @JvmStatic
        fun buildExternalPrincipalId(aclKey: AclKey, principalType: PrincipalType): String {
            return "$INTERNAL_PREFIX|${principalType.toString().toLowerCase()}|${aclKey.last()}"
        }
    }
}