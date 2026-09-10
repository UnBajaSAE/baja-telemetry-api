package br.unb.baja.telemetry.config

import br.unb.baja.telemetry.domain.can.dbc.DbcDatabase
import br.unb.baja.telemetry.domain.can.dbc.DbcLoader
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DbcConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Carrega o DBC na subida.
     *
     * Se o arquivo estiver invalido, a aplicacao NAO sobe. E deliberado: uma API
     * de pe com o mapa de sinais quebrado gravaria dado errado em silencio, que
     * e pior do que nao subir (ADR-006).
     */
    @Bean
    fun dbcDatabase(): DbcDatabase = DbcLoader.doClasspath().also {
        log.info(
            "Mapa de sinais carregado: versao '{}', {} mensagens, {} sinais",
            it.version, it.messages.size, it.signalCount,
        )
        if (it.version.contains("PROVISORIO", ignoreCase = true)) {
            log.warn(
                "O DBC e PROVISORIO: os valores sao ficticios e nao refletem o firmware real. " +
                    "Ver docs/05 §5.1 para o levantamento.",
            )
        }
    }
}
