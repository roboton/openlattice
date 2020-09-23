package com.openlattice.transporter.types

import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.kryptnostic.rhizome.configuration.RhizomeConfiguration
import com.openlattice.assembler.AssemblerConfiguration
import com.openlattice.assembler.PostgresDatabases
import com.openlattice.postgres.external.ExternalDatabaseConnectionManager
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.*

/**
 * TransporterDatastore configures entries in [rhizome] postgres configuration
 */
@Component
class TransporterDatastore(
        private val assemblerConfiguration: AssemblerConfiguration,
        rhizome: RhizomeConfiguration,
        private val exConnMan: ExternalDatabaseConnectionManager
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TransporterDatastore::class.java)
        private val PAT = Regex("""([\w:]+)://([\w_.]*):(\d+)/(\w+)""")
    }
    private val fdwName = "enterprise"
    private val fdwSchema = "ol"
    private var hds: HikariDataSource = exConnMan.createDataSource(
            "transporter",
            assemblerConfiguration.server.clone() as Properties,
            assemblerConfiguration.ssl
    )

    init {
        logger.info("Initializing TransporterDatastore")
        if (rhizome.postgresConfiguration.isPresent) {
            initializeFDW(rhizome.postgresConfiguration.get())
        }
        ensureSearchPath()
    }

    private fun ensureSearchPath() {
        logger.info("checking search path for current user")
        hds.connection.use { conn ->
            val st = conn.createStatement()
            st.executeQuery( "show search_path" ).use {
                it.next()
                val searchPath = it.getString(1)
                logger.info(searchPath)
                if ( !searchPath.contains( fdwSchema )) {
                    logger.error("bad search path: {}", searchPath)
                }
            }
        }
    }

    private fun initializeFDW(enterprise: PostgresConfiguration) {
        hds.connection.use { conn ->
            conn.autoCommit = false
            val st = conn.createStatement()
            st.executeQuery("select count(*) from information_schema.foreign_tables where foreign_table_schema = '$fdwSchema'").use { rs ->
                if (rs.next() && rs.getInt(1) > 0) {
                    // don't bother if it's already there
                    logger.info("fdw already exists, not re-creating")
                    return
                }
            }
            val url = enterprise.hikariConfiguration.getProperty("jdbcUrl")
            logger.info("Configuring fdw from {} to {}", assemblerConfiguration.server.getProperty("jdbcUrl"), url)
            val match = PAT.matchEntire(url) ?: throw IllegalArgumentException("Invalid jdbc url: $url")
            // 0 = whole string, 1 = prefix, 2 = hostname, 3 = port, 4 = database
            val hostname = match.groupValues[2]
            val port = match.groupValues[3].toInt()
            val dbname = match.groupValues[4]
            val user = assemblerConfiguration.server.getProperty("username")
            val remoteUser = enterprise.hikariConfiguration.getProperty("username")
            val remotePassword = enterprise.hikariConfiguration.getProperty("password")
            """
                |create extension if not exists postgres_fdw;
                |create server if not exists $fdwName foreign data wrapper postgres_fdw options (host '$hostname', dbname '$dbname', port '$port');
                |create user mapping if not exists for $user server $fdwName options (user '$remoteUser', password '$remotePassword');
                |create schema if not exists $fdwSchema;
                |import foreign schema public from server $fdwName INTO $fdwSchema;
                |alter user $user set search_path to public, $fdwSchema;
                |set search_path to public, $fdwSchema;
            """
                    .trimMargin()
                    .split("\n")
                    .forEach { sql ->
                        logger.info("running {}", sql)
                        st.execute(sql)
                    }
            conn.commit()
        }
        hds.close()
        hds = exConnMan.connect("transporter")
    }

    fun datastore(): HikariDataSource {
        return hds
    }

    fun createOrgDataSource(organizationId: UUID): HikariDataSource {
        return exConnMan.connect( PostgresDatabases.buildOrganizationDatabaseName( organizationId ) )
    }

}