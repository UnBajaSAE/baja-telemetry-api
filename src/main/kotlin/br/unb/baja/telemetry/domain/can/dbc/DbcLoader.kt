package br.unb.baja.telemetry.domain.can.dbc

/**
 * Carrega o mapa de sinais do classpath.
 *
 * O arquivo vem de `contracts/can/unbaja.dbc` e e copiado pelo build (docs/05 §2).
 * A leitura acontece UMA vez, na subida da aplicacao -- nao por frame.
 */
object DbcLoader {

    const val CAMINHO = "/can/unbaja.dbc"

    fun doClasspath(caminho: String = CAMINHO): DbcDatabase {
        val texto = DbcLoader::class.java.getResourceAsStream(caminho)
            ?.bufferedReader()?.use { it.readText() }
            ?: throw DbcParseException(
                "mapa de sinais nao encontrado no classpath em '$caminho'.\n" +
                    "  A fonte e contracts/can/unbaja.dbc, copiada pelo processResources.",
            )
        return DbcParser.parse(texto)
    }
}
