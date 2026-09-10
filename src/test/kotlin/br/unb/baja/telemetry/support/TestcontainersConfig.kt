package br.unb.baja.telemetry.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * O Postgres dos testes -- de verdade, num container (ADR-003).
 *
 * UM container para a suite inteira, nao um por classe. O `by lazy` de um `object`
 * garante isso: o container sobe na primeira vez que alguem precisa e e reusado
 * por todas as classes seguintes. Vinte classes com container proprio seriam
 * vinte partidas de Postgres, e uma suite que ninguem espera terminar.
 */
object PostgresDeTeste {

    /**
     * A mesma imagem do docker-compose.yml. Testar contra um `postgres:17` puro
     * daria falsa confianca: sem a extensao, `create_hypertable` nao existe, e e
     * justamente isso que este projeto usa.
     *
     * O `asCompatibleSubstituteFor` e necessario porque o Testcontainers so
     * reconhece imagens chamadas "postgres"; esta se chama "timescale/timescaledb".
     */
    val instancia: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:latest-pg17")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName("baja")
            .withUsername("baja")
            .withPassword("baja")
            .also { it.start() }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    /** `@ServiceConnection` aponta o datasource da aplicacao para o container. */
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgresDeTeste.instancia
}
